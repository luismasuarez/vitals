use std::path::Path;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result};
use rusqlite::{params, Connection, OptionalExtension};
use serde::Serialize;

use crate::caps::Scope;

pub fn now_ts() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

#[derive(Debug, Clone, Serialize)]
pub struct Point {
    pub t: i64,
    pub cpu: f64,
    #[serde(rename = "mem")]
    pub mem_used: i64,
    pub net_rx: i64,
    pub net_tx: i64,
    pub disk_r: i64,
    pub disk_w: i64,
}

#[derive(Debug, Clone, Serialize)]
pub struct Device {
    pub id: String,
    pub name: String,
    #[serde(skip)]
    pub token_hash: String,
    pub scope: String,
    pub created_at: i64,
    pub last_seen_at: Option<i64>,
}

#[derive(Debug, Clone, Serialize)]
pub struct AuditRow {
    pub id: i64,
    pub at: i64,
    pub device_id: String,
    pub op: String,
    pub request_id: Option<String>,
    pub outcome: String,
    pub code: Option<String>,
    pub detail: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct CapRequest {
    pub id: i64,
    pub device_id: String,
    pub device_name: String,
    pub cap: String,
    pub params: String,
    pub created_at: i64,
    pub expires_at: i64,
}

const SCHEMA: &str = r#"
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA busy_timeout = 5000;

CREATE TABLE IF NOT EXISTS devices (
    id           TEXT PRIMARY KEY,
    name         TEXT NOT NULL,
    token_hash   TEXT NOT NULL UNIQUE,
    scope        TEXT NOT NULL DEFAULT 'reader',
    created_at   INTEGER NOT NULL,
    last_seen_at INTEGER
);

CREATE TABLE IF NOT EXISTS pairing_codes (
    code_hash TEXT PRIMARY KEY,
    expires_at INTEGER NOT NULL,
    scope      TEXT NOT NULL DEFAULT 'reader'
);

CREATE TABLE IF NOT EXISTS audit (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    at         INTEGER NOT NULL,
    device_id  TEXT NOT NULL,
    op         TEXT NOT NULL,
    request_id TEXT,
    outcome    TEXT NOT NULL,
    code       TEXT,
    detail     TEXT
);

CREATE INDEX IF NOT EXISTS audit_at ON audit(at);

CREATE TABLE IF NOT EXISTS command_results (
    device_id TEXT NOT NULL,
    id        TEXT NOT NULL,
    at        INTEGER NOT NULL,
    response  TEXT NOT NULL,
    PRIMARY KEY (device_id, id)
);

CREATE TABLE IF NOT EXISTS capability_requests (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id  TEXT NOT NULL,
    cap        TEXT NOT NULL,
    params     TEXT NOT NULL,
    code_hash  TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    state      TEXT NOT NULL DEFAULT 'pending',
    approved_at INTEGER,
    source     TEXT
);

CREATE TABLE IF NOT EXISTS samples_raw (
    t        INTEGER PRIMARY KEY,
    cpu      REAL NOT NULL,
    mem_used INTEGER NOT NULL,
    net_rx   INTEGER NOT NULL,
    net_tx   INTEGER NOT NULL,
    disk_r   INTEGER NOT NULL,
    disk_w   INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS samples_1m (
    t        INTEGER PRIMARY KEY,
    cpu      REAL NOT NULL,
    mem_used INTEGER NOT NULL,
    net_rx   INTEGER NOT NULL,
    net_tx   INTEGER NOT NULL,
    disk_r   INTEGER NOT NULL,
    disk_w   INTEGER NOT NULL
);
"#;

/// Adds a column to a table created by an older schema. `table` and `decl`
/// are internal constants, never user input.
fn ensure_column(conn: &Connection, table: &str, column: &str, decl: &str) -> Result<()> {
    let mut stmt = conn.prepare(&format!("PRAGMA table_info({table})"))?;
    let mut rows = stmt.query([])?;
    let mut present = false;
    while let Some(row) = rows.next()? {
        let name: String = row.get(1)?;
        if name == column {
            present = true;
            break;
        }
    }
    drop(rows);
    drop(stmt);
    if !present {
        conn.execute_batch(&format!("ALTER TABLE {table} ADD COLUMN {column} {decl}"))
            .with_context(|| format!("cannot add {table}.{column}"))?;
    }
    Ok(())
}

pub struct Store {
    conn: Mutex<Connection>,
}

impl Store {
    pub fn open(path: &Path) -> Result<Self> {
        let conn = Connection::open(path)
            .with_context(|| format!("cannot open database {}", path.display()))?;
        Self::init(conn)
    }

    fn init(conn: Connection) -> Result<Self> {
        conn.execute_batch(SCHEMA).context("cannot apply schema")?;
        // Databases created by 0.1.x predate scopes and capabilities.
        ensure_column(&conn, "devices", "scope", "TEXT NOT NULL DEFAULT 'reader'")?;
        ensure_column(
            &conn,
            "pairing_codes",
            "scope",
            "TEXT NOT NULL DEFAULT 'reader'",
        )?;
        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Connection> {
        self.conn.lock().expect("store mutex poisoned")
    }

    pub fn insert_raw(&self, p: &Point) -> Result<()> {
        self.lock().execute(
            "INSERT OR REPLACE INTO samples_raw (t, cpu, mem_used, net_rx, net_tx, disk_r, disk_w)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![p.t, p.cpu, p.mem_used, p.net_rx, p.net_tx, p.disk_r, p.disk_w],
        )?;
        Ok(())
    }

    /// Aggregates complete minutes of raw samples into samples_1m.
    pub fn rollup(&self, now: i64) -> Result<usize> {
        let cutoff = (now / 60) * 60;
        let n = self.lock().execute(
            "INSERT INTO samples_1m (t, cpu, mem_used, net_rx, net_tx, disk_r, disk_w)
             SELECT (t / 60) * 60,
                    AVG(cpu),
                    CAST(AVG(mem_used) AS INTEGER),
                    CAST(AVG(net_rx) AS INTEGER),
                    CAST(AVG(net_tx) AS INTEGER),
                    CAST(AVG(disk_r) AS INTEGER),
                    CAST(AVG(disk_w) AS INTEGER)
             FROM samples_raw
             WHERE t < ?1
             GROUP BY (t / 60) * 60
             ON CONFLICT(t) DO UPDATE SET
                 cpu = excluded.cpu, mem_used = excluded.mem_used,
                 net_rx = excluded.net_rx, net_tx = excluded.net_tx,
                 disk_r = excluded.disk_r, disk_w = excluded.disk_w",
            params![cutoff],
        )?;
        Ok(n)
    }

    pub fn prune(&self, now: i64, raw_hours: u32, days_1m: u32) -> Result<()> {
        let c = self.lock();
        c.execute(
            "DELETE FROM samples_raw WHERE t < ?1",
            params![now - raw_hours as i64 * 3600],
        )?;
        c.execute(
            "DELETE FROM samples_1m WHERE t < ?1",
            params![now - days_1m as i64 * 86_400],
        )?;
        c.execute(
            "DELETE FROM pairing_codes WHERE expires_at < ?1",
            params![now],
        )?;
        c.execute(
            "DELETE FROM command_results WHERE at < ?1",
            params![now - 86_400],
        )?;
        c.execute(
            "DELETE FROM capability_requests WHERE expires_at < ?1",
            params![now - 86_400],
        )?;
        Ok(())
    }

    /// 90 days or `max_rows`, whichever comes first.
    pub fn prune_audit(&self, now: i64, max_rows: usize) -> Result<()> {
        let c = self.lock();
        c.execute(
            "DELETE FROM audit WHERE at < ?1",
            params![now - 90 * 86_400],
        )?;
        c.execute(
            "DELETE FROM audit WHERE id NOT IN (SELECT id FROM audit ORDER BY id DESC LIMIT ?1)",
            params![max_rows as i64],
        )?;
        Ok(())
    }

    /// `table` is a whitelisted internal name, never user input.
    pub fn query(&self, table: &str, from: i64, to: i64, step: i64) -> Result<Vec<Point>> {
        let table = match table {
            "samples_raw" => "samples_raw",
            _ => "samples_1m",
        };
        let sql = format!(
            "SELECT (t / ?1) * ?1 AS b,
                    AVG(cpu),
                    CAST(AVG(mem_used) AS INTEGER),
                    CAST(AVG(net_rx) AS INTEGER),
                    CAST(AVG(net_tx) AS INTEGER),
                    CAST(AVG(disk_r) AS INTEGER),
                    CAST(AVG(disk_w) AS INTEGER)
             FROM {table}
             WHERE t >= ?2 AND t <= ?3
             GROUP BY b
             ORDER BY b"
        );
        let conn = self.lock();
        let mut stmt = conn.prepare(&sql)?;
        let rows = stmt.query_map(params![step, from, to], |r| {
            Ok(Point {
                t: r.get(0)?,
                cpu: r.get(1)?,
                mem_used: r.get(2)?,
                net_rx: r.get(3)?,
                net_tx: r.get(4)?,
                disk_r: r.get(5)?,
                disk_w: r.get(6)?,
            })
        })?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row?);
        }
        Ok(out)
    }

    // --- pairing -----------------------------------------------------------

    pub fn add_pairing_code(&self, code_hash: &str, ttl_secs: u64, scope: Scope) -> Result<i64> {
        let expires = now_ts() + ttl_secs as i64;
        self.lock().execute(
            "INSERT OR REPLACE INTO pairing_codes (code_hash, expires_at, scope) VALUES (?1, ?2, ?3)",
            params![code_hash, expires, scope.as_str()],
        )?;
        Ok(expires)
    }

    /// Single-use: a successful consume deletes the code and returns the scope
    /// it was issued with.
    pub fn consume_pairing_code(&self, code_hash: &str) -> Option<Scope> {
        let conn = self.lock();
        let scope: Option<String> = conn
            .query_row(
                "DELETE FROM pairing_codes WHERE code_hash = ?1 AND expires_at >= ?2
                 RETURNING scope",
                params![code_hash, now_ts()],
                |row| row.get(0),
            )
            .optional()
            .ok()
            .flatten();
        scope.as_deref().and_then(Scope::parse)
    }

    // --- devices -----------------------------------------------------------

    pub fn insert_device(
        &self,
        id: &str,
        name: &str,
        token_hash: &str,
        scope: Scope,
    ) -> Result<()> {
        self.lock().execute(
            "INSERT INTO devices (id, name, token_hash, scope, created_at, last_seen_at)
             VALUES (?1, ?2, ?3, ?4, ?5, NULL)",
            params![id, name, token_hash, scope.as_str(), now_ts()],
        )?;
        Ok(())
    }

    pub fn find_device(&self, id: &str) -> Option<Device> {
        self.lock()
            .query_row(
                "SELECT id, name, token_hash, scope, created_at, last_seen_at
                 FROM devices WHERE id = ?1",
                params![id],
                |r| {
                    Ok(Device {
                        id: r.get(0)?,
                        name: r.get(1)?,
                        token_hash: r.get(2)?,
                        scope: r.get(3)?,
                        created_at: r.get(4)?,
                        last_seen_at: r.get(5)?,
                    })
                },
            )
            .optional()
            .ok()
            .flatten()
    }

    pub fn list_devices(&self) -> Vec<Device> {
        let mut out = Vec::new();
        let conn = self.lock();
        let Ok(mut stmt) = conn.prepare(
            "SELECT id, name, token_hash, scope, created_at, last_seen_at
             FROM devices ORDER BY created_at",
        ) else {
            return out;
        };
        let Ok(rows) = stmt.query_map([], |r| {
            Ok(Device {
                id: r.get(0)?,
                name: r.get(1)?,
                token_hash: r.get(2)?,
                scope: r.get(3)?,
                created_at: r.get(4)?,
                last_seen_at: r.get(5)?,
            })
        }) else {
            return out;
        };
        for row in rows.flatten() {
            out.push(row);
        }
        out
    }

    pub fn revoke_device(&self, id: &str) -> bool {
        self.lock()
            .execute("DELETE FROM devices WHERE id = ?1", params![id])
            .unwrap_or(0)
            == 1
    }

    /// Throttled to one write per minute per device.
    pub fn touch_device(&self, id: &str) {
        let now = now_ts();
        let _ = self.lock().execute(
            "UPDATE devices SET last_seen_at = ?1
             WHERE id = ?2 AND (last_seen_at IS NULL OR last_seen_at < ?1 - 60)",
            params![now, id],
        );
    }

    pub fn count_devices(&self) -> i64 {
        self.lock()
            .query_row("SELECT COUNT(*) FROM devices", [], |r| r.get(0))
            .unwrap_or(0)
    }

    // --- audit -------------------------------------------------------------

    /// Every `command` attempt lands here, rejections included.
    #[allow(clippy::too_many_arguments)]
    pub fn insert_audit(
        &self,
        at: i64,
        device_id: &str,
        op: &str,
        request_id: Option<&str>,
        outcome: &str,
        code: Option<&str>,
        detail: Option<&str>,
    ) -> Result<()> {
        self.lock().execute(
            "INSERT INTO audit (at, device_id, op, request_id, outcome, code, detail)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![at, device_id, op, request_id, outcome, code, detail],
        )?;
        Ok(())
    }

    pub fn query_audit(&self, limit: usize, before: Option<i64>) -> Vec<AuditRow> {
        let limit = limit.clamp(1, 500) as i64;
        let conn = self.lock();
        let (sql, args): (&str, Vec<i64>) = match before {
            Some(before) => (
                "SELECT id, at, device_id, op, request_id, outcome, code, detail
                 FROM audit WHERE id < ?1 ORDER BY id DESC LIMIT ?2",
                vec![before, limit],
            ),
            None => (
                "SELECT id, at, device_id, op, request_id, outcome, code, detail
                 FROM audit ORDER BY id DESC LIMIT ?1",
                vec![limit],
            ),
        };
        let Ok(mut stmt) = conn.prepare(sql) else {
            return Vec::new();
        };
        let mapped = stmt.query_map(rusqlite::params_from_iter(args), |r| {
            Ok(AuditRow {
                id: r.get(0)?,
                at: r.get(1)?,
                device_id: r.get(2)?,
                op: r.get(3)?,
                request_id: r.get(4)?,
                outcome: r.get(5)?,
                code: r.get(6)?,
                detail: r.get(7)?,
            })
        });
        let Ok(rows) = mapped else {
            return Vec::new();
        };
        rows.flatten().collect()
    }

    // --- command idempotency ----------------------------------------------

    pub fn find_command_result(&self, device_id: &str, id: &str, since: i64) -> Option<String> {
        self.lock()
            .query_row(
                "SELECT response FROM command_results
                 WHERE device_id = ?1 AND id = ?2 AND at >= ?3",
                params![device_id, id, since],
                |row| row.get(0),
            )
            .optional()
            .ok()
            .flatten()
    }

    pub fn insert_command_result(&self, device_id: &str, id: &str, response: &str) -> Result<()> {
        self.lock().execute(
            "INSERT OR REPLACE INTO command_results (device_id, id, at, response)
             VALUES (?1, ?2, ?3, ?4)",
            params![device_id, id, now_ts(), response],
        )?;
        Ok(())
    }

    // --- capability requests ----------------------------------------------

    /// A new request for the same device and capability replaces the previous
    /// pending one, so a stale code can never be approved later.
    pub fn insert_cap_request(
        &self,
        device_id: &str,
        cap: &str,
        params_json: &str,
        code_hash: &str,
        ttl_secs: u64,
    ) -> Result<i64> {
        let now = now_ts();
        let conn = self.lock();
        conn.execute(
            "DELETE FROM capability_requests
             WHERE device_id = ?1 AND cap = ?2 AND state = 'pending'",
            params![device_id, cap],
        )?;
        conn.execute(
            "INSERT INTO capability_requests
                (device_id, cap, params, code_hash, created_at, expires_at, state)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, 'pending')",
            params![
                device_id,
                cap,
                params_json,
                code_hash,
                now,
                now + ttl_secs as i64
            ],
        )?;
        Ok(conn.last_insert_rowid())
    }

    pub fn pending_cap_requests(&self) -> Vec<CapRequest> {
        self.cap_requests(
            "SELECT r.id, r.device_id, COALESCE(d.name, ''), r.cap, r.params, r.created_at, r.expires_at
             FROM capability_requests r LEFT JOIN devices d ON d.id = r.device_id
             WHERE r.state = 'pending' AND r.expires_at >= ?1
             ORDER BY r.created_at",
            (now_ts(),),
        )
    }

    pub fn cap_request_by_code(&self, code_hash: &str) -> Option<CapRequest> {
        self.cap_requests(
            "SELECT r.id, r.device_id, COALESCE(d.name, ''), r.cap, r.params, r.created_at, r.expires_at
             FROM capability_requests r LEFT JOIN devices d ON d.id = r.device_id
             WHERE r.state = 'pending' AND r.expires_at >= ?1 AND r.code_hash = ?2",
            params![now_ts(), code_hash],
        )
        .into_iter()
        .next()
    }

    fn cap_requests(&self, sql: &str, args: impl rusqlite::Params) -> Vec<CapRequest> {
        let conn = self.lock();
        let Ok(mut stmt) = conn.prepare(sql) else {
            return Vec::new();
        };
        let Ok(rows) = stmt.query_map(args, |r| {
            Ok(CapRequest {
                id: r.get(0)?,
                device_id: r.get(1)?,
                device_name: r.get(2)?,
                cap: r.get(3)?,
                params: r.get(4)?,
                created_at: r.get(5)?,
                expires_at: r.get(6)?,
            })
        }) else {
            return Vec::new();
        };
        rows.flatten().collect()
    }

    /// Marking is separate from writing the manifest: the CLI writes the grant
    /// first and only then marks the request approved.
    pub fn approve_cap_request(&self, id: i64, source: &str) -> bool {
        self.lock()
            .execute(
                "UPDATE capability_requests
                 SET state = 'approved', approved_at = ?1, source = ?2
                 WHERE id = ?3 AND state = 'pending'",
                params![now_ts(), source, id],
            )
            .unwrap_or(0)
            == 1
    }
}

#[cfg(test)]
impl Store {
    pub fn in_memory() -> Self {
        Self::init(Connection::open_in_memory().unwrap()).unwrap()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn point(t: i64, cpu: f64, rx: i64) -> Point {
        Point {
            t,
            cpu,
            mem_used: 1024,
            net_rx: rx,
            net_tx: 1,
            disk_r: 2,
            disk_w: 3,
        }
    }

    #[test]
    fn pair_codes_are_single_use() {
        let s = Store::in_memory();
        s.add_pairing_code("abc", 300, Scope::Operator).unwrap();
        assert_eq!(s.consume_pairing_code("abc"), Some(Scope::Operator));
        assert_eq!(s.consume_pairing_code("abc"), None);
    }

    #[test]
    fn expired_pair_codes_are_rejected() {
        let s = Store::in_memory();
        {
            let c = s.lock();
            c.execute(
                "INSERT INTO pairing_codes (code_hash, expires_at, scope) VALUES ('old', ?1, 'reader')",
                params![now_ts() - 5],
            )
            .unwrap();
        }
        assert_eq!(s.consume_pairing_code("old"), None);
    }

    #[test]
    fn devices_roundtrip_and_revoke() {
        let s = Store::in_memory();
        s.insert_device("id1", "phone", "hash", Scope::Operator)
            .unwrap();
        assert_eq!(s.find_device("id1").unwrap().name, "phone");
        assert_eq!(s.find_device("id1").unwrap().scope, "operator");
        assert_eq!(s.count_devices(), 1);
        assert!(s.revoke_device("id1"));
        assert!(s.find_device("id1").is_none());
        assert!(!s.revoke_device("id1"));
    }

    #[test]
    fn audit_keeps_the_newest_rows() {
        let s = Store::in_memory();
        s.insert_audit(
            100,
            "dev",
            "exec.unit_restart",
            Some("r1"),
            "rejected",
            Some("capability_not_granted"),
            None,
        )
        .unwrap();
        s.insert_audit(
            200,
            "dev",
            "caps.request",
            Some("r2"),
            "accepted",
            None,
            Some("exec.journal_vacuum"),
        )
        .unwrap();
        let rows = s.query_audit(10, None);
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].request_id.as_deref(), Some("r2"));
        // Cursor pagination walks backwards without duplicates.
        let older = s.query_audit(10, Some(rows[0].id));
        assert_eq!(older.len(), 1);
        assert_eq!(older[0].request_id.as_deref(), Some("r1"));
    }

    #[test]
    fn command_results_are_idempotent_for_24h() {
        let s = Store::in_memory();
        let now = now_ts();
        s.insert_command_result("dev", "cmd-1", "{\"ok\":true}")
            .unwrap();
        assert_eq!(
            s.find_command_result("dev", "cmd-1", now - 60),
            Some("{\"ok\":true}".into())
        );
        assert_eq!(s.find_command_result("dev", "cmd-1", now + 1), None);
        assert_eq!(s.find_command_result("other", "cmd-1", now - 60), None);
    }

    #[test]
    fn capability_requests_are_single_and_per_device() {
        let s = Store::in_memory();
        s.insert_device("a", "phone-a", "hash-a", Scope::Operator)
            .unwrap();
        s.insert_device("b", "phone-b", "hash-b", Scope::Operator)
            .unwrap();
        let id = s
            .insert_cap_request("a", "exec.unit_restart", "{}", "code-a", 600)
            .unwrap();
        let second = s
            .insert_cap_request("a", "exec.unit_restart", "{}", "code-b", 600)
            .unwrap();
        // The previous pending request for the same device and cap is gone.
        let pending = s.pending_cap_requests();
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].id, second);
        assert_eq!(pending[0].device_name, "phone-a");
        assert!(s.cap_request_by_code("code-a").is_none());
        assert_eq!(s.cap_request_by_code("code-b").unwrap().id, second);

        assert!(s.approve_cap_request(second, "list"));
        assert!(!s.approve_cap_request(second, "list"));
        assert!(s.pending_cap_requests().is_empty());
        // Approval on A does not create anything for B.
        assert!(s.pending_cap_requests().iter().all(|r| r.device_id != "b"));
        assert_eq!(id + 1, second);
    }

    #[test]
    fn audit_is_pruned_by_age_and_row_count() {
        let s = Store::in_memory();
        let now = now_ts();
        s.insert_audit(
            now - 91 * 86_400,
            "dev",
            "old",
            None,
            "accepted",
            None,
            None,
        )
        .unwrap();
        for i in (0..5).rev() {
            s.insert_audit(now - i, "dev", "op", None, "accepted", None, None)
                .unwrap();
        }
        s.prune_audit(now, 2).unwrap();
        let rows = s.query_audit(10, None);
        assert_eq!(rows.len(), 2);
        // The two newest rows survive, the expired one is gone.
        assert_eq!(rows[0].at, now);
        assert_eq!(rows[1].at, now - 1);
    }

    #[test]
    fn old_devices_schema_migrates_to_reader_scope() {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(
            "CREATE TABLE devices (
                 id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE,
                 created_at INTEGER NOT NULL, last_seen_at INTEGER
             );
             INSERT INTO devices VALUES ('old', 'legacy', 'hash', 1, NULL);",
        )
        .unwrap();
        let s = Store::init(conn).unwrap();
        assert_eq!(s.find_device("old").unwrap().scope, "reader");
    }

    #[test]
    fn rollup_averages_complete_minutes() {
        let s = Store::in_memory();
        let base = 1_700_000_040; // multiple of 60
        s.insert_raw(&point(base + 1, 10.0, 100)).unwrap();
        s.insert_raw(&point(base + 2, 30.0, 300)).unwrap();
        // A minute only rolls up once it is complete: at base+60 the bucket
        // [base, base+59] is closed and safe to aggregate.
        let n = s.rollup(base + 60).unwrap();
        assert_eq!(n, 1);
        let rows = s.query("samples_1m", 0, i64::MAX, 60).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].t, base);
        assert!((rows[0].cpu - 20.0).abs() < f64::EPSILON);
        assert_eq!(rows[0].net_rx, 200);
    }

    #[test]
    fn prune_drops_old_rows() {
        let s = Store::in_memory();
        s.insert_raw(&point(1000, 1.0, 1)).unwrap();
        s.insert_raw(&point(now_ts(), 1.0, 1)).unwrap();
        s.prune(now_ts(), 24, 30).unwrap();
        assert_eq!(s.query("samples_raw", 0, i64::MAX, 10).unwrap().len(), 1);
    }
}

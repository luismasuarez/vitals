use std::path::Path;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result};
use rusqlite::{params, Connection, OptionalExtension};
use serde::Serialize;

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
    pub created_at: i64,
    pub last_seen_at: Option<i64>,
}

const SCHEMA: &str = r#"
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;
PRAGMA busy_timeout = 5000;

CREATE TABLE IF NOT EXISTS devices (
    id           TEXT PRIMARY KEY,
    name         TEXT NOT NULL,
    token_hash   TEXT NOT NULL UNIQUE,
    created_at   INTEGER NOT NULL,
    last_seen_at INTEGER
);

CREATE TABLE IF NOT EXISTS pairing_codes (
    code_hash TEXT PRIMARY KEY,
    expires_at INTEGER NOT NULL
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

    pub fn add_pairing_code(&self, code_hash: &str, ttl_secs: u64) -> Result<i64> {
        let expires = now_ts() + ttl_secs as i64;
        self.lock().execute(
            "INSERT OR REPLACE INTO pairing_codes (code_hash, expires_at) VALUES (?1, ?2)",
            params![code_hash, expires],
        )?;
        Ok(expires)
    }

    /// Single-use: a successful consume deletes the code.
    pub fn consume_pairing_code(&self, code_hash: &str) -> bool {
        let n = self
            .lock()
            .execute(
                "DELETE FROM pairing_codes WHERE code_hash = ?1 AND expires_at >= ?2",
                params![code_hash, now_ts()],
            )
            .unwrap_or(0);
        n == 1
    }

    // --- devices -----------------------------------------------------------

    pub fn insert_device(&self, id: &str, name: &str, token_hash: &str) -> Result<()> {
        self.lock().execute(
            "INSERT INTO devices (id, name, token_hash, created_at, last_seen_at)
             VALUES (?1, ?2, ?3, ?4, NULL)",
            params![id, name, token_hash, now_ts()],
        )?;
        Ok(())
    }

    pub fn find_device(&self, id: &str) -> Option<Device> {
        self.lock()
            .query_row(
                "SELECT id, name, token_hash, created_at, last_seen_at FROM devices WHERE id = ?1",
                params![id],
                |r| {
                    Ok(Device {
                        id: r.get(0)?,
                        name: r.get(1)?,
                        token_hash: r.get(2)?,
                        created_at: r.get(3)?,
                        last_seen_at: r.get(4)?,
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
            "SELECT id, name, token_hash, created_at, last_seen_at FROM devices ORDER BY created_at",
        ) else {
            return out;
        };
        let Ok(rows) = stmt.query_map([], |r| {
            Ok(Device {
                id: r.get(0)?,
                name: r.get(1)?,
                token_hash: r.get(2)?,
                created_at: r.get(3)?,
                last_seen_at: r.get(4)?,
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
        s.add_pairing_code("abc", 300).unwrap();
        assert!(s.consume_pairing_code("abc"));
        assert!(!s.consume_pairing_code("abc"));
    }

    #[test]
    fn expired_pair_codes_are_rejected() {
        let s = Store::in_memory();
        {
            let c = s.lock();
            c.execute(
                "INSERT INTO pairing_codes (code_hash, expires_at) VALUES ('old', ?1)",
                params![now_ts() - 5],
            )
            .unwrap();
        }
        assert!(!s.consume_pairing_code("old"));
    }

    #[test]
    fn devices_roundtrip_and_revoke() {
        let s = Store::in_memory();
        s.insert_device("id1", "phone", "hash").unwrap();
        assert_eq!(s.find_device("id1").unwrap().name, "phone");
        assert_eq!(s.count_devices(), 1);
        assert!(s.revoke_device("id1"));
        assert!(s.find_device("id1").is_none());
        assert!(!s.revoke_device("id1"));
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

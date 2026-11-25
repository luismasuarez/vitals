//! SSH sessions and login history.
//!
//! Current sessions come from systemd-logind's session files (or `utmp` when
//! systemd is not there). Accepted and failed SSH authentications come from the
//! journal and from `/var/log/auth.log*`, merged and de-duplicated, because a
//! host may keep one, the other or both.
//!
//! Nothing here changes state: the agent only reads files and asks journalctl
//! for lines that are already on disk.

use std::collections::HashMap;
use std::fs;
use std::path::Path;
use std::process::Command;
use std::sync::Arc;
use std::time::{Duration, Instant};

use chrono::{DateTime, Datelike, Local, NaiveDateTime, TimeZone};
use serde::Serialize;

use crate::store::now_ts;

const SESSION_REFRESH: Duration = Duration::from_secs(5);
const LOG_REFRESH: Duration = Duration::from_secs(60);
pub const LOG_WINDOW_SECS: i64 = 7 * 86_400;
const DAY_SECS: i64 = 86_400;
/// `journalctl -n` keeps a brute-forced host from turning a refresh into a scan
/// of millions of lines.
const JOURNAL_LINES: &str = "20000";
const MAX_EVENTS: usize = 100;
const MAX_SOURCES: usize = 100;
const UTMP_RECORD: usize = 384;
const UTMP_USER_PROCESS: u16 = 7;

#[derive(Debug, Clone, Serialize)]
pub struct Session {
    pub user: String,
    pub tty: String,
    pub service: String,
    pub remote: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub host: Option<String>,
    pub since: i64,
}

#[derive(Debug, Clone, Serialize)]
pub struct AuthEvent {
    pub t: i64,
    pub ok: bool,
    pub user: String,
    pub host: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub method: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub port: Option<u16>,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub invalid_user: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct Source {
    pub host: String,
    /// True when the address is neither private nor loopback.
    pub public: bool,
    pub ok: u32,
    pub failed: u32,
    pub first_t: i64,
    pub last_t: i64,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub users: Vec<String>,
    /// Seen for the first time inside the last 24 h, with a window long enough
    /// to tell "new" from "old".
    pub new: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct SshConfig {
    pub password_auth: bool,
    pub root_login: String,
    pub files: Vec<String>,
}

impl SshConfig {
    fn unavailable() -> Self {
        Self {
            password_auth: true,
            root_login: "prohibit-password".into(),
            files: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct AccessSnapshot {
    pub updated: i64,
    /// `journal`, `auth.log`, `journal+auth.log` or `none`.
    pub source: String,
    /// How much history the events actually cover, capped at the log window.
    pub covered_s: i64,
    pub sessions: Vec<Session>,
    pub logins: Vec<AuthEvent>,
    pub failures: Vec<AuthEvent>,
    pub sources: Vec<Source>,
    pub ssh: SshConfig,
    pub logins_24h: u32,
    pub failures_24h: u32,
    pub logins_7d: u32,
    pub failures_7d: u32,
    pub last_login: Option<AuthEvent>,
    pub last_failure: Option<AuthEvent>,
}

/// The small object that travels with every `/metrics` sample.
#[derive(Debug, Clone, Serialize)]
pub struct Access {
    pub sessions: Vec<Session>,
    pub logins_24h: u32,
    pub failures_24h: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_login: Option<AuthEvent>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_failure: Option<AuthEvent>,
    pub password_auth: bool,
    /// False when no sshd config file could be read, so the two values above
    /// are defaults rather than facts.
    pub config_read: bool,
    pub root_login: String,
    pub source: String,
}

impl AccessSnapshot {
    pub fn summary(&self) -> Access {
        Access {
            sessions: self.sessions.clone(),
            logins_24h: self.logins_24h,
            failures_24h: self.failures_24h,
            last_login: self.last_login.clone(),
            last_failure: self.last_failure.clone(),
            password_auth: self.ssh.password_auth,
            config_read: !self.ssh.files.is_empty(),
            root_login: self.ssh.root_login.clone(),
            source: self.source.clone(),
        }
    }
}

/// Refreshes sessions often and log analysis once a minute.
pub struct Collector {
    snapshot: Option<Arc<AccessSnapshot>>,
    last_sessions: Instant,
    last_logs: Instant,
}

impl Default for Collector {
    fn default() -> Self {
        Self::new()
    }
}

impl Collector {
    pub fn new() -> Self {
        let now = Instant::now();
        // Force both refreshes on the first tick.
        Self {
            snapshot: None,
            last_sessions: now.checked_sub(SESSION_REFRESH).unwrap_or(now),
            last_logs: now.checked_sub(LOG_REFRESH).unwrap_or(now),
        }
    }

    /// Returns the new snapshot only when something was refreshed.
    pub fn tick(&mut self) -> Option<Arc<AccessSnapshot>> {
        let now = Instant::now();
        let sessions_due = now.duration_since(self.last_sessions) >= SESSION_REFRESH;
        let logs_due = now.duration_since(self.last_logs) >= LOG_REFRESH;
        if !sessions_due && !logs_due {
            return None;
        }
        if sessions_due {
            self.last_sessions = now;
        }
        if logs_due {
            self.last_logs = now;
        }

        let sessions = read_sessions();
        let snapshot = if logs_due || self.snapshot.is_none() {
            build_snapshot(sessions, now_ts())
        } else {
            let mut previous = (*self.snapshot.clone()?).clone();
            previous.sessions = sessions;
            previous.updated = now_ts();
            previous
        };

        let snapshot = Arc::new(snapshot);
        self.snapshot = Some(snapshot.clone());
        Some(snapshot)
    }
}

/// Sorts newest first and collapses the same attempt read from two sources.
fn sort_and_dedupe(events: &mut Vec<AuthEvent>) {
    events.sort_by(|a, b| {
        (a.t, &a.user, &a.host, a.ok, &a.method, a.port)
            .cmp(&(b.t, &b.user, &b.host, b.ok, &b.method, b.port))
    });
    events.dedup_by(|a, b| {
        // The journal and auth.log record the same attempt with the same port,
        // method and second; two real attempts within the same bucket would
        // almost never share all three.
        let same = a.t / 2 == b.t / 2
            && a.user == b.user
            && a.host == b.host
            && a.ok == b.ok
            && a.method == b.method
            && a.port == b.port;
        if same {
            b.invalid_user |= a.invalid_user;
        }
        same
    });
    events.sort_by_key(|e| std::cmp::Reverse(e.t));
}

fn build_snapshot(sessions: Vec<Session>, now: i64) -> AccessSnapshot {
    let (mut events, source) = read_events(now);
    sort_and_dedupe(&mut events);

    let covered_s = events
        .last()
        .map(|e| (now - e.t).clamp(0, LOG_WINDOW_SECS))
        .unwrap_or(0);

    let mut sources: Vec<Source> = Vec::new();
    let mut index: HashMap<String, usize> = HashMap::new();
    for event in &events {
        let position = *index.entry(event.host.clone()).or_insert_with(|| {
            sources.push(Source {
                host: event.host.clone(),
                public: is_public(&event.host),
                ok: 0,
                failed: 0,
                first_t: event.t,
                last_t: event.t,
                users: Vec::new(),
                new: false,
            });
            sources.len() - 1
        });
        let slot = &mut sources[position];
        if event.ok {
            slot.ok += 1;
        } else {
            slot.failed += 1;
        }
        slot.first_t = slot.first_t.min(event.t);
        slot.last_t = slot.last_t.max(event.t);
        if slot.users.len() < 8 && !slot.users.iter().any(|user| user == &event.user) {
            slot.users.push(event.user.clone());
        }
    }
    sources.sort_by_key(|s| std::cmp::Reverse(s.last_t));
    sources.truncate(MAX_SOURCES);
    for source in &mut sources {
        source.new = covered_s >= DAY_SECS && source.first_t >= now - DAY_SECS;
    }

    let mut logins = Vec::new();
    let mut failures = Vec::new();
    let (mut logins_24h, mut failures_24h) = (0, 0);
    let (mut logins_7d, mut failures_7d) = (0, 0);
    let mut last_login = None;
    let mut last_failure = None;
    for event in &events {
        if event.ok {
            logins_7d += 1;
            if event.t >= now - DAY_SECS {
                logins_24h += 1;
            }
            if last_login.is_none() {
                last_login = Some(event.clone());
            }
            if logins.len() < MAX_EVENTS {
                logins.push(event.clone());
            }
        } else {
            failures_7d += 1;
            if event.t >= now - DAY_SECS {
                failures_24h += 1;
            }
            if last_failure.is_none() {
                last_failure = Some(event.clone());
            }
            if failures.len() < MAX_EVENTS {
                failures.push(event.clone());
            }
        }
    }

    AccessSnapshot {
        updated: now,
        source,
        covered_s,
        sessions,
        logins,
        failures,
        sources,
        ssh: read_ssh_config(),
        logins_24h,
        failures_24h,
        logins_7d,
        failures_7d,
        last_login,
        last_failure,
    }
}

/* --------------------------------- sessions -------------------------------- */

fn read_sessions() -> Vec<Session> {
    let mut sessions = read_systemd_sessions();
    if sessions.is_empty() {
        sessions = read_utmp();
    }
    sessions.sort_by_key(|session| std::cmp::Reverse(session.since));
    sessions
}

fn read_systemd_sessions() -> Vec<Session> {
    let Ok(entries) = fs::read_dir("/run/systemd/sessions") else {
        return Vec::new();
    };
    let mut sessions = Vec::new();
    for entry in entries.flatten() {
        let Ok(kind) = entry.file_type() else {
            continue;
        };
        // The directory also holds `.ref` FIFOs; reading one would block.
        if !kind.is_file() {
            continue;
        }
        let Ok(raw) = fs::read_to_string(entry.path()) else {
            continue;
        };
        if let Some(session) = parse_session_file(&raw) {
            sessions.push(session);
        }
    }
    sessions
}

fn parse_session_file(raw: &str) -> Option<Session> {
    let mut user = None;
    let mut service = None;
    let mut tty = None;
    let mut host = None;
    let mut remote = false;
    let mut active = false;
    let mut class = None;
    let mut realtime = None;

    for line in raw.lines() {
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        match key {
            "USER" => user = Some(value.trim().to_string()),
            "SERVICE" => service = Some(value.trim().to_string()),
            "TTY" => tty = Some(value.trim().to_string()),
            "REMOTE_HOST" => host = Some(value.trim().to_string()),
            "REMOTE" => remote = value.trim() == "1",
            "ACTIVE" => active = value.trim() == "1",
            "CLASS" => class = Some(value.trim().to_string()),
            "REALTIME" => realtime = value.trim().parse::<i64>().ok(),
            _ => {}
        }
    }

    let user = user.filter(|user| !user.is_empty())?;
    if !active || class.as_deref() == Some("manager") {
        return None;
    }
    if service.as_deref() == Some("systemd-user") {
        return None;
    }

    Some(Session {
        user,
        tty: tty.unwrap_or_default(),
        service: service.unwrap_or_else(|| "?".into()),
        remote,
        host: host.filter(|host| !host.is_empty()),
        since: realtime.unwrap_or(0) / 1_000_000,
    })
}

fn read_utmp() -> Vec<Session> {
    for path in ["/run/utmp", "/var/run/utmp"] {
        let path = Path::new(path);
        if !path.is_file() {
            continue;
        }
        if let Ok(raw) = fs::read(path) {
            return parse_utmp(&raw);
        }
    }
    Vec::new()
}

fn parse_utmp(raw: &[u8]) -> Vec<Session> {
    raw.chunks_exact(UTMP_RECORD)
        .filter_map(parse_utmp_record)
        .collect()
}

/// Linux `struct utmp` is a fixed 384-byte record on disk. Only the fields we
/// show are read: line, user, host and the login time.
fn parse_utmp_record(record: &[u8]) -> Option<Session> {
    let kind = u16::from_ne_bytes([record[0], record[1]]);
    if kind != UTMP_USER_PROCESS {
        return None;
    }
    let user = cstr(&record[44..76])?;
    let tty = cstr(&record[8..40]).unwrap_or_default();
    let host = cstr(&record[76..332]).unwrap_or_default();
    let since = i32::from_ne_bytes(record[340..344].try_into().ok()?) as i64;
    Some(Session {
        user,
        tty,
        service: "login".into(),
        remote: !host.is_empty(),
        host: (!host.is_empty()).then_some(host),
        since,
    })
}

fn cstr(raw: &[u8]) -> Option<String> {
    let end = raw.iter().position(|byte| *byte == 0).unwrap_or(raw.len());
    let value = String::from_utf8_lossy(&raw[..end]).trim().to_string();
    (!value.is_empty()).then_some(value)
}

/* ------------------------------- auth logs -------------------------------- */

fn read_events(now: i64) -> (Vec<AuthEvent>, String) {
    let mut events = Vec::new();
    let mut sources = Vec::new();

    if let Some(raw) = read_journal() {
        sources.push("journal");
        parse_lines(&raw, now, &mut events);
    }
    if let Some(raw) = read_auth_logs() {
        sources.push("auth.log");
        parse_lines(&raw, now, &mut events);
    }

    let source = if sources.is_empty() {
        "none".into()
    } else {
        sources.join("+")
    };
    (events, source)
}

fn read_journal() -> Option<String> {
    let output = Command::new("journalctl")
        .args([
            "-u",
            "ssh",
            "-u",
            "sshd",
            "--since",
            "-7d",
            "-n",
            JOURNAL_LINES,
            "-o",
            "short-iso",
            "--no-pager",
        ])
        .env("LC_ALL", "C")
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    Some(String::from_utf8_lossy(&output.stdout).into_owned())
}

fn read_auth_logs() -> Option<String> {
    let mut text = String::new();
    let mut read = false;
    for path in [
        "/var/log/auth.log",
        "/var/log/auth.log.1",
        "/var/log/secure",
    ] {
        if let Ok(raw) = fs::read_to_string(path) {
            read = true;
            text.push_str(&raw);
            if !raw.ends_with('\n') {
                text.push('\n');
            }
        }
    }
    read.then_some(text)
}

fn parse_lines(raw: &str, now: i64, events: &mut Vec<AuthEvent>) {
    let now_local = Local::now();
    let year = now_local.format("%Y").to_string();
    for line in raw.lines() {
        let Some((stamp, message)) = split_ssh_line(line) else {
            continue;
        };
        let Some(t) = parse_timestamp(stamp, now, &year) else {
            continue;
        };
        if t < now - LOG_WINDOW_SECS - 60 || t > now + 300 {
            continue;
        }
        if let Some(event) = parse_message(t, message) {
            events.push(event);
        }
    }
}

fn split_ssh_line(line: &str) -> Option<(&str, &str)> {
    for marker in ["sshd[", "sshd-session["] {
        if let Some(index) = line.find(marker) {
            let after = &line[index..];
            let colon = after.find("]: ")?;
            return Some((line[..index].trim_end(), &after[colon + 3..]));
        }
    }
    None
}

/// The prefix also carries the hostname; keep just the timestamp.
fn timestamp_part(prefix: &str) -> &str {
    if prefix.len() > 10 && prefix.as_bytes()[4] == b'-' {
        prefix.split_whitespace().next().unwrap_or("")
    } else if prefix.len() >= 15 {
        &prefix[..15]
    } else {
        prefix
    }
}

fn parse_timestamp(stamp: &str, now: i64, year: &str) -> Option<i64> {
    let stamp = timestamp_part(stamp.trim());
    if stamp.is_empty() {
        return None;
    }
    if stamp.len() > 10 && stamp.as_bytes()[4] == b'-' {
        return DateTime::parse_from_rfc3339(stamp)
            .ok()
            .map(|date| date.timestamp());
    }
    // Classic syslog has no year or time zone. Assume the current year in the
    // host's local zone and roll back one year across New Year.
    let parsed = NaiveDateTime::parse_from_str(&format!("{year} {stamp}"), "%Y %b %e %H:%M:%S")
        .ok()
        .and_then(|naive| Local.from_local_datetime(&naive).earliest())?;
    let mut seconds = parsed.timestamp();
    if seconds > now + DAY_SECS {
        let previous = parsed.with_year(parsed.year() - 1)?;
        seconds = previous.timestamp();
    }
    Some(seconds)
}

fn parse_message(t: i64, message: &str) -> Option<AuthEvent> {
    let message = message.strip_prefix("error: ").unwrap_or(message);
    if let Some(rest) = message.strip_prefix("Accepted ") {
        let (method, rest) = rest.split_once(" for ")?;
        let (user, rest) = rest.split_once(" from ")?;
        let (host, port) = split_host_port(rest)?;
        return Some(AuthEvent {
            t,
            ok: true,
            user: clean(user),
            host: clean(host),
            method: Some(clean(method)),
            port,
            invalid_user: false,
        });
    }
    if let Some(rest) = message.strip_prefix("Failed ") {
        let (method, rest) = rest.split_once(" for ")?;
        let (invalid_user, rest) = strip_invalid(rest);
        let (user, rest) = rest.split_once(" from ")?;
        let (host, port) = split_host_port(rest)?;
        return Some(AuthEvent {
            t,
            ok: false,
            user: clean(user),
            host: clean(host),
            method: Some(clean(method)),
            port,
            invalid_user,
        });
    }
    if let Some(rest) = message.strip_prefix("Invalid user ") {
        let (user, rest) = rest.split_once(" from ")?;
        let (host, port) = split_host_port(rest)?;
        return Some(AuthEvent {
            t,
            ok: false,
            user: clean(user),
            host: clean(host),
            method: None,
            port,
            invalid_user: true,
        });
    }
    if let Some((_, rest)) = message.split_once("maximum authentication attempts exceeded for ") {
        let (invalid_user, rest) = strip_invalid(rest);
        let (user, rest) = rest.split_once(" from ")?;
        let (host, port) = split_host_port(rest)?;
        return Some(AuthEvent {
            t,
            ok: false,
            user: clean(user),
            host: clean(host),
            method: None,
            port,
            invalid_user,
        });
    }
    None
}

fn strip_invalid(rest: &str) -> (bool, &str) {
    match rest.strip_prefix("invalid user ") {
        Some(rest) => (true, rest),
        None => (false, rest),
    }
}

fn split_host_port(rest: &str) -> Option<(&str, Option<u16>)> {
    let mut parts = rest.split(' ');
    let host = parts.next()?;
    let port = match (parts.next(), parts.next()) {
        (Some("port"), Some(port)) => port.parse().ok(),
        _ => None,
    };
    Some((host, port))
}

fn clean(value: &str) -> String {
    value.chars().filter(|c| !c.is_control()).take(64).collect()
}

fn is_public(host: &str) -> bool {
    let Ok(ip) = host.parse::<std::net::IpAddr>() else {
        return false;
    };
    match ip {
        std::net::IpAddr::V4(ip) => {
            let octets = ip.octets();
            !(ip.is_private()
                || ip.is_loopback()
                || ip.is_link_local()
                || ip.is_unspecified()
                || ip.is_broadcast()
                || (octets[0] == 100 && (64..128).contains(&octets[1])))
        }
        std::net::IpAddr::V6(ip) => {
            // `is_unique_local` and `is_unicast_link_local` need Rust 1.84 and
            // the crate keeps an older MSRV, so the prefixes are checked here.
            let segments = ip.segments();
            let unique_local = segments[0] & 0xfe00 == 0xfc00;
            let link_local = segments[0] & 0xffc0 == 0xfe80;
            !(ip.is_loopback() || ip.is_unspecified() || unique_local || link_local)
        }
    }
}

/* ------------------------------- sshd config ------------------------------- */

fn read_ssh_config() -> SshConfig {
    let mut config = SshConfig::unavailable();
    let mut files = Vec::new();
    let mut password_auth = None;
    let mut root_login = None;

    let drop_ins = read_drop_ins();
    let paths: Vec<std::path::PathBuf> = drop_ins
        .into_iter()
        .chain(std::iter::once("/etc/ssh/sshd_config".into()))
        .collect();

    for path in paths {
        let Ok(raw) = fs::read_to_string(&path) else {
            continue;
        };
        files.push(path.display().to_string());
        for line in raw.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let mut parts = line.split_whitespace();
            let Some(key) = parts.next() else {
                continue;
            };
            let value = parts.next().unwrap_or_default();
            match key.to_ascii_lowercase().as_str() {
                "passwordauthentication" if password_auth.is_none() => {
                    password_auth = Some(!value.eq_ignore_ascii_case("no"));
                }
                "permitrootlogin" if root_login.is_none() => {
                    root_login = Some(value.to_ascii_lowercase());
                }
                _ => {}
            }
        }
    }

    if let Some(value) = password_auth {
        config.password_auth = value;
    }
    if let Some(value) = root_login {
        config.root_login = value;
    }
    config.files = files;
    config
}

fn read_drop_ins() -> Vec<std::path::PathBuf> {
    let Ok(entries) = fs::read_dir("/etc/ssh/sshd_config.d") else {
        return Vec::new();
    };
    let mut files: Vec<std::path::PathBuf> = entries
        .flatten()
        .map(|entry| entry.path())
        .filter(|path| path.extension().is_some_and(|ext| ext == "conf"))
        .collect();
    files.sort();
    files
}

#[cfg(test)]
mod tests {
    use super::*;

    fn utmp_record(kind: u16, user: &str, line: &str, host: &str, since: i32) -> Vec<u8> {
        let mut record = vec![0u8; UTMP_RECORD];
        record[..2].copy_from_slice(&kind.to_ne_bytes());
        record[8..8 + line.len()].copy_from_slice(line.as_bytes());
        record[44..44 + user.len()].copy_from_slice(user.as_bytes());
        record[76..76 + host.len()].copy_from_slice(host.as_bytes());
        record[340..344].copy_from_slice(&since.to_ne_bytes());
        record
    }

    #[test]
    fn utmp_keeps_user_sessions_and_skips_other_records() {
        let mut raw = utmp_record(7, "root", "pts/0", "203.0.113.9", 1_700_000_000);
        raw.extend(utmp_record(2, "", "", "", 0)); // BOOT_TIME
        raw.extend(utmp_record(7, "luisma", "tty1", "", 1_700_000_100));
        let sessions = parse_utmp(&raw);
        assert_eq!(sessions.len(), 2);
        assert_eq!(sessions[0].user, "root");
        assert!(sessions[0].remote);
        assert_eq!(sessions[0].host.as_deref(), Some("203.0.113.9"));
        assert_eq!(sessions[1].user, "luisma");
        assert!(!sessions[1].remote);
    }

    #[test]
    fn systemd_session_files_expose_remote_and_local_sessions() {
        let remote = "USER=root\nACTIVE=1\nREMOTE=1\nREMOTE_HOST=198.51.100.7\nSERVICE=sshd\nTTY=pts/0\nREALTIME=1700000000000000\n";
        let local = "USER=luisma\nACTIVE=1\nREMOTE=0\nSERVICE=login\nCLASS=user\nREALTIME=1700000100000000\n";
        let manager = "USER=luisma\nACTIVE=1\nCLASS=manager\nSERVICE=systemd-user\nREALTIME=1700000200000000\n";
        let inactive = "USER=root\nACTIVE=0\nCLASS=user\nSERVICE=sshd\nREALTIME=1700000300000000\n";

        let parsed = parse_session_file(remote).unwrap();
        assert_eq!(parsed.user, "root");
        assert_eq!(parsed.host.as_deref(), Some("198.51.100.7"));
        assert_eq!(parsed.since, 1_700_000_000);

        let parsed = parse_session_file(local).unwrap();
        assert!(!parsed.remote);
        assert!(parsed.host.is_none());

        assert!(parse_session_file(manager).is_none());
        assert!(parse_session_file(inactive).is_none());
    }

    #[test]
    fn auth_messages_cover_password_publickey_and_scanners() {
        let accepted = parse_message(
            100,
            "Accepted publickey for root from 198.51.100.7 port 58635 ssh2: ED25519 SHA256:abc",
        )
        .unwrap();
        assert!(accepted.ok);
        assert_eq!(accepted.method.as_deref(), Some("publickey"));
        assert_eq!(accepted.user, "root");
        assert_eq!(accepted.port, Some(58635));

        let failed = parse_message(
            101,
            "Failed password for invalid user admin from 203.0.113.5 port 41102 ssh2",
        )
        .unwrap();
        assert!(!failed.ok);
        assert!(failed.invalid_user);
        assert_eq!(failed.user, "admin");

        let invalid = parse_message(102, "Invalid user ubuntu from 203.0.113.6 port 22").unwrap();
        assert!(!invalid.ok);
        assert!(invalid.invalid_user);

        let maxed = parse_message(
            103,
            "error: maximum authentication attempts exceeded for root from 203.0.113.7 port 22 ssh2 [preauth]",
        )
        .unwrap();
        assert!(!maxed.ok);

        assert!(parse_message(104, "session opened for user root(uid=0) by root(uid=0)").is_none());
        assert!(
            parse_message(105, "Received disconnect from 203.0.113.8 port 22:11: Bye").is_none()
        );
    }

    #[test]
    fn complete_auth_lines_with_hostname_are_parsed() {
        let line = "2026-11-23T12:38:02.259503-05:00 steampunker sshd-session[1321687]: Accepted password for luisma from 192.168.1.101 port 55554 ssh2";
        let (prefix, message) = split_ssh_line(line).unwrap();
        let expected = DateTime::parse_from_rfc3339("2026-11-23T12:38:02.259503-05:00")
            .unwrap()
            .timestamp();
        let t = parse_timestamp(prefix, expected + 3600, "2026").unwrap();
        assert_eq!(t, expected);
        let event = parse_message(t, message).unwrap();
        assert!(event.ok);
        assert_eq!(event.method.as_deref(), Some("password"));
        assert_eq!(event.user, "luisma");
        assert_eq!(event.host, "192.168.1.101");

        let classic = "Nov 24 09:18:02 steampunker sshd[123]: Failed password for ubuntu from 203.0.113.5 port 41102 ssh2";
        let (prefix, message) = split_ssh_line(classic).unwrap();
        assert_eq!(timestamp_part(prefix), "Nov 24 09:18:02");
        assert!(parse_timestamp(prefix, expected + 3600, "2026").is_some());
        assert!(!parse_message(0, message).unwrap().ok);
    }

    #[test]
    fn duplicate_sources_collapse_but_real_attempts_do_not() {
        let same = "Failed password for root from 203.0.113.9 port 41102 ssh2";
        let other_port = "Failed password for root from 203.0.113.9 port 41103 ssh2";
        let invalid = "Failed password for invalid user admin from 203.0.113.9 port 41102 ssh2";
        let mut events = vec![
            parse_message(100, same).unwrap(),
            parse_message(100, same).unwrap(),
            parse_message(100, other_port).unwrap(),
            parse_message(101, invalid).unwrap(),
            parse_message(
                50,
                "Accepted publickey for root from 203.0.113.9 port 50000 ssh2",
            )
            .unwrap(),
        ];

        sort_and_dedupe(&mut events);
        assert_eq!(events.len(), 4);
        assert_eq!(events[0].t, 101);
        assert!(events[0].invalid_user);
        assert_eq!(events[3].t, 50);
    }

    #[test]
    fn classic_syslog_timestamps_roll_back_a_year() {
        let now = DateTime::parse_from_rfc3339("2026-01-02T00:00:00Z")
            .unwrap()
            .with_timezone(&Local);
        let year = now.format("%Y").to_string();
        let stamp = "Dec 31 23:50:00";
        let parsed = parse_timestamp(stamp, now.timestamp(), &year).unwrap();
        assert!(parsed < now.timestamp());
        assert!(now.timestamp() - parsed < 2 * DAY_SECS);
    }

    #[test]
    fn external_addresses_are_flagged() {
        assert!(is_public("203.0.113.9"));
        assert!(!is_public("192.168.1.10"));
        assert!(!is_public("10.0.0.2"));
        assert!(!is_public("127.0.0.1"));
        assert!(!is_public("100.71.72.48"));
        assert!(!is_public("::1"));
        assert!(!is_public("fd7a:115c:a1e0::1"));
        assert!(!is_public("not-an-ip"));
    }

    #[test]
    fn new_sources_require_a_window_older_than_a_day() {
        // Sources are marked in build_snapshot only when coverage allows it;
        // this exercises the rule itself through a hand-built snapshot.
        let now = 1_700_000_000;
        let mut source = Source {
            host: "203.0.113.9".into(),
            public: true,
            ok: 1,
            failed: 0,
            first_t: now - 3600,
            last_t: now - 3600,
            users: vec!["root".into()],
            new: false,
        };
        let covered = 2 * DAY_SECS;
        source.new = covered >= DAY_SECS && source.first_t >= now - DAY_SECS;
        assert!(source.new);

        source.first_t = now - 3 * DAY_SECS;
        source.new = covered >= DAY_SECS && source.first_t >= now - DAY_SECS;
        assert!(!source.new);
    }
}

use std::collections::HashMap;
use std::fs::File;
use std::io::Read;
use std::net::{IpAddr, SocketAddr};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use axum::extract::{ConnectInfo, Request, State};
use axum::http::{header, StatusCode};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;

use crate::api::AppState;
use crate::caps::Scope;

/// Crockford base32: no I, L, O, U, so an 8-character code cannot be misread.
const CROCKFORD: &[u8] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";

pub const GENERAL_LIMIT_PER_MIN: u32 = 240;
pub const PAIR_LIMIT_PER_MIN: u32 = 5;
pub const COMMAND_LIMIT_PER_MIN: u32 = 60;

const BUCKET_GENERAL: u8 = 0;
const BUCKET_PAIR: u8 = 1;

/// The authenticated caller, available to handlers after `require_token`.
#[derive(Debug, Clone)]
pub struct AuthDevice {
    pub id: String,
    pub scope: Scope,
}

pub fn random_bytes(n: usize) -> Vec<u8> {
    let mut buf = vec![0u8; n];
    File::open("/dev/urandom")
        .and_then(|mut f| f.read_exact(&mut buf))
        .expect("cannot read /dev/urandom");
    buf
}

pub fn hex(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push_str(&format!("{b:02x}"));
    }
    s
}

pub fn random_hex(n: usize) -> String {
    hex(&random_bytes(n))
}

pub fn sha256_hex(input: &str) -> String {
    hex(&Sha256::digest(input.as_bytes()))
}

/// Constant-time comparison, used for every credential check.
pub fn eq_ct(a: &str, b: &str) -> bool {
    a.len() == b.len() && bool::from(a.as_bytes().ct_eq(b.as_bytes()))
}

/// 40 bits of entropy rendered as XXXX-XXXX.
pub fn pairing_code() -> String {
    let raw = random_bytes(5);
    let mut acc: u64 = 0;
    for b in &raw {
        acc = (acc << 8) | *b as u64;
    }
    let mut out = String::with_capacity(9);
    for i in (0..8).rev() {
        let idx = ((acc >> (i * 5)) & 0x1f) as usize;
        out.push(CROCKFORD[idx] as char);
    }
    format!("{}-{}", &out[..4], &out[4..])
}

/// 30 bits rendered as six characters: typed on the host to approve a
/// capability request from another machine. Same alphabet as the pairing code,
/// without the dash.
pub fn approval_code() -> String {
    random_bytes(6)
        .into_iter()
        .map(|b| CROCKFORD[(b & 0x1f) as usize] as char)
        .collect()
}

/// Accepts "abcd-efgh", "ABCDEFGH" and the classic OCR confusions.
pub fn normalize_code(input: &str) -> String {
    input
        .chars()
        .filter(|c| !c.is_whitespace() && *c != '-')
        .map(|c| match c.to_ascii_uppercase() {
            'O' => '0',
            'I' | 'L' => '1',
            other => other,
        })
        .collect()
}

pub fn new_device_token() -> (String, String) {
    let device_id = random_hex(6);
    let secret = random_hex(32);
    (device_id.clone(), format!("{device_id}.{secret}"))
}

pub struct RateLimiter {
    buckets: Mutex<HashMap<(u8, IpAddr), (u32, Instant)>>,
}

impl Default for RateLimiter {
    fn default() -> Self {
        Self {
            buckets: Mutex::new(HashMap::new()),
        }
    }
}

impl RateLimiter {
    pub fn allow(&self, bucket: u8, ip: IpAddr, max: u32) -> bool {
        let now = Instant::now();
        let mut map = self.buckets.lock().expect("rate limiter poisoned");
        if map.len() > 4096 {
            map.retain(|_, (_, at)| now.duration_since(*at) < Duration::from_secs(120));
        }
        let entry = map.entry((bucket, ip)).or_insert((0, now));
        if now.duration_since(entry.1) >= Duration::from_secs(60) {
            *entry = (0, now);
        }
        entry.0 += 1;
        entry.0 <= max
    }
}

/// Per-device budget for `POST /api/v1/command`. IPs are useless here: several
/// devices can share a NAT address, and one device must not starve another.
pub struct DeviceLimiter {
    buckets: Mutex<HashMap<String, (u32, Instant)>>,
}

impl Default for DeviceLimiter {
    fn default() -> Self {
        Self {
            buckets: Mutex::new(HashMap::new()),
        }
    }
}

impl DeviceLimiter {
    pub fn allow(&self, device_id: &str, max: u32) -> bool {
        let now = Instant::now();
        let mut map = self.buckets.lock().expect("device limiter poisoned");
        if map.len() > 1024 {
            map.retain(|_, (_, at)| now.duration_since(*at) < Duration::from_secs(120));
        }
        let entry = map.entry(device_id.to_string()).or_insert((0, now));
        if now.duration_since(entry.1) >= Duration::from_secs(60) {
            *entry = (0, now);
        }
        entry.0 += 1;
        entry.0 <= max
    }
}

fn peer_ip(req: &Request) -> IpAddr {
    req.extensions()
        .get::<ConnectInfo<SocketAddr>>()
        .map(|c| c.0.ip())
        .unwrap_or(IpAddr::from([127, 0, 0, 1]))
}

fn unauthorized() -> Response {
    (StatusCode::UNAUTHORIZED, "unauthorized").into_response()
}

/// Rejects the request unless it carries a valid device token.
pub async fn require_token(State(st): State<AppState>, mut req: Request, next: Next) -> Response {
    let ip = peer_ip(&req);
    if !st.limiter.allow(BUCKET_GENERAL, ip, GENERAL_LIMIT_PER_MIN) {
        return (StatusCode::TOO_MANY_REQUESTS, "slow down").into_response();
    }

    let Some(value) = req.headers().get(header::AUTHORIZATION) else {
        return unauthorized();
    };
    let Ok(value) = value.to_str() else {
        return unauthorized();
    };
    let Some(token) = value.strip_prefix("Bearer ") else {
        return unauthorized();
    };
    let Some((device_id, _secret)) = token.split_once('.') else {
        return unauthorized();
    };
    if device_id.is_empty() || device_id.len() > 32 {
        return unauthorized();
    }

    let Some(device) = st.store.find_device(device_id) else {
        return unauthorized();
    };
    if !eq_ct(&device.token_hash, &sha256_hex(token)) {
        return unauthorized();
    }

    st.store.touch_device(&device.id);
    let scope = Scope::parse(&device.scope).unwrap_or(Scope::Reader);
    req.extensions_mut().insert(AuthDevice {
        id: device.id,
        scope,
    });
    next.run(req).await
}

/// Applied after `require_token` to the two operator-only routes. A `reader`
/// that tries `command` is still an attempt, so it gets an audit row.
pub async fn require_operator(State(st): State<AppState>, req: Request, next: Next) -> Response {
    let Some(device) = req.extensions().get::<AuthDevice>().cloned() else {
        return unauthorized();
    };
    if device.scope != Scope::Operator {
        if req.uri().path() == "/api/v1/command" {
            let _ = st.store.insert_audit(
                crate::store::now_ts(),
                &device.id,
                "unknown",
                None,
                "rejected",
                Some("denied"),
                Some("operator scope required"),
            );
        }
        return (
            StatusCode::FORBIDDEN,
            axum::Json(serde_json::json!({ "error": "operator scope required" })),
        )
            .into_response();
    }
    next.run(req).await
}

/// Applied only to POST /api/v1/pair: the one endpoint without a token.
pub async fn pair_rate_limit(State(st): State<AppState>, req: Request, next: Next) -> Response {
    let ip = peer_ip(&req);
    if !st.limiter.allow(BUCKET_PAIR, ip, PAIR_LIMIT_PER_MIN) {
        return (StatusCode::TOO_MANY_REQUESTS, "too many pairing attempts").into_response();
    }
    next.run(req).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_are_eight_chars_with_dash() {
        let code = pairing_code();
        assert_eq!(code.len(), 9);
        assert_eq!(code.as_bytes()[4], b'-');
    }

    #[test]
    fn normalizes_ocr_confusions() {
        assert_eq!(normalize_code(" abcd-efgh "), "ABCDEFGH");
        assert_eq!(normalize_code("0O1I"), "0011");
        assert_eq!(normalize_code("abcd-efgh"), "ABCDEFGH");
    }

    #[test]
    fn token_is_id_dot_secret() {
        let (id, token) = new_device_token();
        assert!(token.starts_with(&id));
        assert_eq!(token.split('.').count(), 2);
        assert_eq!(id.len(), 12);
    }

    #[test]
    fn approval_codes_are_six_unambiguous_chars() {
        let code = approval_code();
        assert_eq!(code.len(), 6);
        assert!(code.bytes().all(|b| CROCKFORD.contains(&b)
            && b != b'I'
            && b != b'L'
            && b != b'O'
            && b != b'U'));
        assert_ne!(approval_code(), code);
    }

    #[test]
    fn device_limiter_is_per_device() {
        let limiter = DeviceLimiter::default();
        assert!(limiter.allow("a", 2));
        assert!(limiter.allow("a", 2));
        assert!(!limiter.allow("a", 2));
        assert!(limiter.allow("b", 2));
    }

    #[test]
    fn constant_time_compare_rejects_prefixes() {
        assert!(eq_ct("abc", "abc"));
        assert!(!eq_ct("abc", "abd"));
        assert!(!eq_ct("abc", "abcd"));
    }
}

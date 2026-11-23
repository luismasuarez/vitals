//! `POST /api/v1/command`: the single mutable endpoint.
//!
//! The body is small, the schema is closed and every attempt is audited.
//! Only `caps.request` produces state in 0.2; `exec.*` is authorized and then
//! answered with `not_implemented`, which keeps the approval path testable
//! without shipping command execution.

use std::path::Path;

use axum::body::Bytes;
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Extension;
use axum::Json;
use serde::Deserialize;
use serde_json::{json, Value};

use crate::auth::{approval_code, normalize_code, sha256_hex, AuthDevice, COMMAND_LIMIT_PER_MIN};
use crate::caps;
use crate::store::{now_ts, Store};

use super::AppState;

pub const MAX_COMMAND_BYTES: usize = 4 * 1024;
const MAX_ID_LEN: usize = 64;
const MAX_DETAIL: usize = 200;
const CAP_REQUEST_TTL_SECS: i64 = 600;
const IDEMPOTENCY_SECS: i64 = 86_400;
const MAX_UNITS: usize = 20;
const MAX_UNIT_LEN: usize = 128;
const MAX_BYTES_LIMIT: u64 = 1_000_000_000_000;

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct CommandRequest {
    v: u32,
    id: String,
    op: Op,
    #[serde(default)]
    args: Value,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
enum Op {
    #[serde(rename = "caps.request")]
    CapsRequest,
    #[serde(rename = "alerts.set")]
    AlertsSet,
    #[serde(rename = "alerts.delete")]
    AlertsDelete,
    #[serde(rename = "alerts.silence")]
    AlertsSilence,
    #[serde(rename = "exec.unit_restart")]
    ExecUnitRestart,
    #[serde(rename = "exec.journal_vacuum")]
    ExecJournalVacuum,
    #[serde(rename = "exec.pkg_cache_clean")]
    ExecPkgCacheClean,
}

impl Op {
    fn as_str(self) -> &'static str {
        match self {
            Op::CapsRequest => "caps.request",
            Op::AlertsSet => "alerts.set",
            Op::AlertsDelete => "alerts.delete",
            Op::AlertsSilence => "alerts.silence",
            Op::ExecUnitRestart => "exec.unit_restart",
            Op::ExecJournalVacuum => "exec.journal_vacuum",
            Op::ExecPkgCacheClean => "exec.pkg_cache_clean",
        }
    }

    fn required_cap(self) -> Option<&'static str> {
        match self {
            Op::CapsRequest => None,
            Op::AlertsSet | Op::AlertsDelete | Op::AlertsSilence => Some("write.alerts"),
            Op::ExecUnitRestart => Some("exec.unit_restart"),
            Op::ExecJournalVacuum => Some("exec.journal_vacuum"),
            Op::ExecPkgCacheClean => Some("exec.pkg_cache_clean"),
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct AlertsSetArgs {
    rules: Vec<crate::alerts::RuleInput>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct AlertsDeleteArgs {
    id: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct AlertsSilenceArgs {
    minutes: u32,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct CapsRequestArgs {
    cap: String,
    #[serde(default)]
    units: Option<Vec<String>>,
    #[serde(default)]
    max_bytes: Option<u64>,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct UnitArgs {
    unit: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct BytesArgs {
    max_bytes: u64,
}

#[derive(Debug)]
struct CmdError {
    status: StatusCode,
    code: &'static str,
    detail: String,
}

impl CmdError {
    fn schema(detail: impl Into<String>) -> Self {
        Self {
            status: StatusCode::BAD_REQUEST,
            code: "schema",
            detail: detail.into(),
        }
    }

    fn capability_not_granted(detail: impl Into<String>) -> Self {
        Self {
            status: StatusCode::FORBIDDEN,
            code: "capability_not_granted",
            detail: detail.into(),
        }
    }

    fn denied(detail: impl Into<String>) -> Self {
        Self {
            status: StatusCode::FORBIDDEN,
            code: "denied",
            detail: detail.into(),
        }
    }
}

pub async fn command(
    State(st): State<AppState>,
    Extension(device): Extension<AuthDevice>,
    body: Bytes,
) -> Response {
    if !st.cmd_limiter.allow(&device.id, COMMAND_LIMIT_PER_MIN) {
        audit(
            &st.store,
            &device,
            "unknown",
            None,
            "rejected",
            Some("rate_limited"),
            Some("more than 60 commands in a minute"),
        );
        return error_response(
            None,
            StatusCode::TOO_MANY_REQUESTS,
            "rate_limited",
            "more than 60 commands in a minute",
        );
    }

    // The route layer already caps the body, but keep the invariant here too.
    if body.len() > MAX_COMMAND_BYTES {
        audit(
            &st.store,
            &device,
            "unknown",
            None,
            "rejected",
            Some("too_large"),
            Some("body exceeds 4096 bytes"),
        );
        return error_response(
            None,
            StatusCode::PAYLOAD_TOO_LARGE,
            "too_large",
            "body exceeds 4096 bytes",
        );
    }

    let req: CommandRequest = match serde_json::from_slice(&body) {
        Ok(req) => req,
        Err(e) => {
            let detail = truncate(&e.to_string());
            audit(
                &st.store,
                &device,
                "unknown",
                None,
                "rejected",
                Some("schema"),
                Some(&detail),
            );
            return error_response(None, StatusCode::BAD_REQUEST, "schema", &detail);
        }
    };

    if req.v != 1 {
        return reject(&st, &device, &req, CmdError::schema("unsupported version"));
    }
    if !valid_id(&req.id) {
        return reject(
            &st,
            &device,
            &req,
            CmdError::schema("id must be 1..64 chars of [A-Za-z0-9_-]"),
        );
    }
    if !req.args.is_object() {
        return reject(
            &st,
            &device,
            &req,
            CmdError::schema("args must be an object"),
        );
    }

    // Replays are answered with the stored success, never re-executed.
    if let Some(stored) =
        st.store
            .find_command_result(&device.id, &req.id, now_ts() - IDEMPOTENCY_SECS)
    {
        audit(
            &st.store,
            &device,
            req.op.as_str(),
            Some(&req.id),
            "replayed",
            None,
            Some("idempotent replay"),
        );
        if let Ok(value) = serde_json::from_str::<Value>(&stored) {
            return (StatusCode::OK, Json(value)).into_response();
        }
    }

    match dispatch(&st.store, &st.caps_manifest, &device, req.op, &req.args) {
        Ok(result) => {
            let response = json!({ "ok": true, "id": req.id, "result": result });
            if let Err(e) =
                st.store
                    .insert_command_result(&device.id, &req.id, &response.to_string())
            {
                tracing::warn!(error = %e, "cannot persist command result");
            }
            audit(
                &st.store,
                &device,
                req.op.as_str(),
                Some(&req.id),
                "accepted",
                None,
                audit_detail(req.op, &req.args).as_deref(),
            );
            (StatusCode::OK, Json(response)).into_response()
        }
        Err(e) => reject(&st, &device, &req, e),
    }
}

fn dispatch(
    store: &Store,
    manifest_path: &Path,
    device: &AuthDevice,
    op: Op,
    args: &Value,
) -> Result<Value, CmdError> {
    match op {
        Op::CapsRequest => request_capability(store, device, args),
        Op::AlertsSet | Op::AlertsDelete | Op::AlertsSilence => {
            let manifest = caps::Manifest::load_or_empty(manifest_path);
            if !caps::is_allowed(&manifest, &device.id, device.scope, "write.alerts") {
                return Err(CmdError::capability_not_granted(
                    "write.alerts is not available for this device",
                ));
            }
            match op {
                Op::AlertsSet => set_alerts(store, args),
                Op::AlertsDelete => delete_alert(store, args),
                Op::AlertsSilence => silence_alerts(store, args),
                _ => unreachable!("alerts ops only"),
            }
        }
        _ => authorized_exec(manifest_path, device, op, args),
    }
}

fn set_alerts(store: &Store, args: &Value) -> Result<Value, CmdError> {
    let args: AlertsSetArgs =
        serde_json::from_value(args.clone()).map_err(|e| CmdError::schema(e.to_string()))?;
    if args.rules.len() > crate::alerts::MAX_RULES {
        return Err(CmdError::schema(format!(
            "at most {} rules are allowed",
            crate::alerts::MAX_RULES
        )));
    }

    let mut ids = std::collections::HashSet::new();
    let mut rules = Vec::with_capacity(args.rules.len());
    for input in &args.rules {
        if !ids.insert(input.id.clone()) {
            return Err(CmdError::schema(format!("duplicate rule id: {}", input.id)));
        }
        rules.push(crate::alerts::parse_rule(input).map_err(CmdError::schema)?);
    }

    store.replace_alert_rules(&rules).map_err(|e| {
        tracing::error!(error = %e, "cannot store alert rules");
        CmdError {
            status: StatusCode::INTERNAL_SERVER_ERROR,
            code: "internal",
            detail: "cannot store alert rules".into(),
        }
    })?;

    Ok(json!({ "rules": store.alert_rules_view() }))
}

fn delete_alert(store: &Store, args: &Value) -> Result<Value, CmdError> {
    let args: AlertsDeleteArgs =
        serde_json::from_value(args.clone()).map_err(|e| CmdError::schema(e.to_string()))?;
    if !crate::alerts::valid_rule_id(&args.id) {
        return Err(CmdError::schema("invalid rule id"));
    }
    Ok(json!({
        "id": args.id,
        "deleted": store.delete_alert_rule(&args.id),
    }))
}

fn silence_alerts(store: &Store, args: &Value) -> Result<Value, CmdError> {
    let args: AlertsSilenceArgs =
        serde_json::from_value(args.clone()).map_err(|e| CmdError::schema(e.to_string()))?;
    if args.minutes == 0 || args.minutes > 1_440 {
        return Err(CmdError::schema("minutes must be in 1..=1440"));
    }
    let now = now_ts();
    let until = now + args.minutes as i64 * 60;
    let silenced = store.silence_alerts(until, now).map_err(|e| {
        tracing::error!(error = %e, "cannot silence alerts");
        CmdError {
            status: StatusCode::INTERNAL_SERVER_ERROR,
            code: "internal",
            detail: "cannot silence alerts".into(),
        }
    })?;
    Ok(json!({
        "silenced_until": until,
        "silenced": silenced,
        "rules": store.alert_rules_view(),
    }))
}

/// `caps.request` is the only path that creates a pending approval. It does not
/// grant anything by itself: the host operator must approve it.
fn request_capability(store: &Store, device: &AuthDevice, args: &Value) -> Result<Value, CmdError> {
    let args: CapsRequestArgs =
        serde_json::from_value(args.clone()).map_err(|e| CmdError::schema(e.to_string()))?;

    if !caps::is_approvable(&args.cap) {
        return Err(CmdError::schema(format!(
            "{} is not an approvable capability",
            args.cap
        )));
    }

    let (params, params_json) = match args.cap.as_str() {
        "exec.unit_restart" => {
            if args.max_bytes.is_some() {
                return Err(CmdError::schema(
                    "max_bytes does not apply to this capability",
                ));
            }
            let units = args
                .units
                .ok_or_else(|| CmdError::schema("units is required for exec.unit_restart"))?;
            if units.is_empty() || units.len() > MAX_UNITS {
                return Err(CmdError::schema(format!(
                    "units must contain 1..{MAX_UNITS} entries"
                )));
            }
            let mut clean = Vec::with_capacity(units.len());
            for unit in units {
                let unit = unit.trim();
                if !valid_unit(unit) {
                    return Err(CmdError::schema(format!("invalid unit name: {unit}")));
                }
                clean.push(unit.to_string());
            }
            clean.sort();
            clean.dedup();
            let json = json!({ "units": clean });
            let table = caps::json_to_toml(&json)
                .and_then(|v| v.as_table().cloned())
                .ok_or_else(|| CmdError::schema("cannot encode grant params"))?;
            (Some(table), json.to_string())
        }
        _ => {
            if args.units.is_some() {
                return Err(CmdError::schema("units does not apply to this capability"));
            }
            let max = args
                .max_bytes
                .ok_or_else(|| CmdError::schema("max_bytes is required for this capability"))?;
            if max == 0 || max > MAX_BYTES_LIMIT {
                return Err(CmdError::schema(format!(
                    "max_bytes must be 1..{MAX_BYTES_LIMIT}"
                )));
            }
            let json = json!({ "max_bytes": max });
            let table = caps::json_to_toml(&json)
                .and_then(|v| v.as_table().cloned())
                .ok_or_else(|| CmdError::schema("cannot encode grant params"))?;
            (Some(table), json.to_string())
        }
    };

    let code = approval_code();
    let id = store
        .insert_cap_request(
            &device.id,
            &args.cap,
            &params_json,
            &code,
            &sha256_hex(&normalize_code(&code)),
            CAP_REQUEST_TTL_SECS as u64,
        )
        .map_err(|e| {
            tracing::error!(error = %e, "cannot store capability request");
            CmdError {
                status: StatusCode::INTERNAL_SERVER_ERROR,
                code: "internal",
                detail: "cannot store request".into(),
            }
        })?;

    let risk = caps::risk_of(&args.cap)
        .map(|r| r.as_str())
        .unwrap_or("unknown");
    Ok(json!({
        "request_id": id,
        "cap": args.cap,
        "risk": risk,
        "code": code,
        "expires_at": now_ts() + CAP_REQUEST_TTL_SECS,
        "params": params.map(|t| caps::toml_to_json(&toml::Value::Table(t))).unwrap_or(Value::Null),
    }))
}

/// Authorizes the reserved `exec.*` operations. They exist in 0.2 only so the
/// per-device grant can be verified end to end; nothing is executed.
fn authorized_exec(
    manifest_path: &Path,
    device: &AuthDevice,
    op: Op,
    args: &Value,
) -> Result<Value, CmdError> {
    let Some(cap) = op.required_cap() else {
        return Err(CmdError::schema("operation has no capability"));
    };
    let manifest = caps::Manifest::load_or_empty(manifest_path);
    if !caps::is_allowed(&manifest, &device.id, device.scope, cap) {
        return Err(CmdError::capability_not_granted(format!(
            "{cap} was not approved for this device"
        )));
    }
    let Some(grant) = manifest.grant_for(&device.id, cap) else {
        return Err(CmdError::capability_not_granted(format!(
            "{cap} was not approved for this device"
        )));
    };

    match op {
        Op::ExecUnitRestart => {
            let args: UnitArgs = serde_json::from_value(args.clone())
                .map_err(|e| CmdError::schema(e.to_string()))?;
            if !valid_unit(&args.unit) {
                return Err(CmdError::schema("invalid unit name"));
            }
            if !grant.permits_unit(&args.unit) {
                return Err(CmdError::denied("unit is outside the approved allow-list"));
            }
        }
        Op::ExecJournalVacuum | Op::ExecPkgCacheClean => {
            let args: BytesArgs = serde_json::from_value(args.clone())
                .map_err(|e| CmdError::schema(e.to_string()))?;
            if args.max_bytes == 0 || args.max_bytes > MAX_BYTES_LIMIT {
                return Err(CmdError::schema(format!(
                    "max_bytes must be 1..{MAX_BYTES_LIMIT}"
                )));
            }
            if !grant.permits_max_bytes(args.max_bytes) {
                return Err(CmdError::denied("size is above the approved maximum"));
            }
        }
        Op::CapsRequest | Op::AlertsSet | Op::AlertsDelete | Op::AlertsSilence => {
            unreachable!("non-exec operation reached authorized_exec")
        }
    }

    Err(CmdError {
        status: StatusCode::NOT_IMPLEMENTED,
        code: "not_implemented",
        detail: format!("{cap} is authorized but execution arrives in 0.4"),
    })
}

fn reject(st: &AppState, device: &AuthDevice, req: &CommandRequest, e: CmdError) -> Response {
    let detail = truncate(&e.detail);
    audit(
        &st.store,
        device,
        req.op.as_str(),
        Some(&req.id),
        "rejected",
        Some(e.code),
        Some(&detail),
    );
    error_response(Some(&req.id), e.status, e.code, &detail)
}

fn error_response(id: Option<&str>, status: StatusCode, code: &str, detail: &str) -> Response {
    let body = match id {
        Some(id) => json!({
            "ok": false,
            "id": id,
            "error": { "code": code, "detail": detail },
        }),
        None => json!({
            "ok": false,
            "error": { "code": code, "detail": detail },
        }),
    };
    (status, Json(body)).into_response()
}

#[allow(clippy::too_many_arguments)]
fn audit(
    store: &Store,
    device: &AuthDevice,
    op: &str,
    request_id: Option<&str>,
    outcome: &str,
    code: Option<&str>,
    detail: Option<&str>,
) {
    if let Err(e) = store.insert_audit(now_ts(), &device.id, op, request_id, outcome, code, detail)
    {
        tracing::warn!(error = %e, "cannot write audit row");
    }
}

fn audit_detail(op: Op, args: &Value) -> Option<String> {
    match op {
        Op::CapsRequest => args
            .get("cap")
            .and_then(Value::as_str)
            .map(|cap| format!("requested {cap}")),
        Op::ExecUnitRestart => args
            .get("unit")
            .and_then(Value::as_str)
            .map(|unit| format!("unit {unit}")),
        Op::ExecJournalVacuum | Op::ExecPkgCacheClean => args
            .get("max_bytes")
            .and_then(Value::as_u64)
            .map(|max| format!("max_bytes {max}")),
        Op::AlertsSet => args
            .get("rules")
            .and_then(Value::as_array)
            .map(|rules| format!("{} rules", rules.len())),
        Op::AlertsDelete => args
            .get("id")
            .and_then(Value::as_str)
            .map(|id| format!("rule {id}")),
        Op::AlertsSilence => args
            .get("minutes")
            .and_then(Value::as_u64)
            .map(|minutes| format!("silence {minutes}m")),
    }
}

fn truncate(text: &str) -> String {
    text.chars().take(MAX_DETAIL).collect()
}

fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= MAX_ID_LEN
        && id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

fn valid_unit(unit: &str) -> bool {
    !unit.is_empty()
        && unit.len() <= MAX_UNIT_LEN
        && unit.contains('.')
        && unit
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '@' | ':' | '-'))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::caps::{Grant, Manifest, Scope};

    fn temp_manifest() -> std::path::PathBuf {
        use std::sync::atomic::{AtomicU64, Ordering};
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let dir = std::env::temp_dir().join(format!(
            "vitals-command-{}-{}-{}",
            std::process::id(),
            now_ts(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir_all(&dir).unwrap();
        dir.join("capabilities.toml")
    }

    fn device(id: &str, scope: Scope) -> AuthDevice {
        AuthDevice {
            id: id.into(),
            scope,
        }
    }

    #[test]
    fn envelope_rejects_unknown_fields_and_ops() {
        let ok = r#"{"v":1,"id":"a","op":"caps.request","args":{}}"#;
        assert!(serde_json::from_str::<CommandRequest>(ok).is_ok());

        let extra = r#"{"v":1,"id":"a","op":"caps.request","args":{},"shell":"rm"}"#;
        assert!(serde_json::from_str::<CommandRequest>(extra).is_err());

        let unknown = r#"{"v":1,"id":"a","op":"exec.run_shell","args":{}}"#;
        assert!(serde_json::from_str::<CommandRequest>(unknown).is_err());
    }

    #[test]
    fn caps_request_validates_params_and_creates_one_pending_row() {
        let store = Store::in_memory();
        store
            .insert_device("a", "phone", "hash", Scope::Operator)
            .unwrap();
        let dev = device("a", Scope::Operator);

        let args = json!({"cap": "exec.unit_restart", "units": ["jellyfin.service"]});
        let result = request_capability(&store, &dev, &args).unwrap();
        assert_eq!(result["cap"], "exec.unit_restart");
        assert_eq!(result["risk"], "high");
        assert_eq!(result["code"].as_str().unwrap().len(), 6);
        assert_eq!(store.pending_cap_requests().len(), 1);

        let bad = json!({"cap": "write.alerts"});
        assert!(request_capability(&store, &dev, &bad).is_err());

        let bad = json!({"cap": "exec.unit_restart", "units": []});
        assert!(request_capability(&store, &dev, &bad).is_err());

        let bad = json!({"cap": "exec.unit_restart", "units": ["../../etc/passwd"]});
        assert!(request_capability(&store, &dev, &bad).is_err());
    }

    #[test]
    fn grants_are_per_device_and_scoped() {
        let path = temp_manifest();
        let store = Store::in_memory();
        let mut manifest = Manifest::new();
        let mut params = toml::Table::new();
        params.insert(
            "units".into(),
            toml::Value::Array(vec![toml::Value::String("jellyfin.service".into())]),
        );
        manifest
            .upsert(Grant::new("a", "exec.unit_restart", Some(params), "list"))
            .unwrap();
        manifest.save(&path).unwrap();

        let args = json!({"unit": "jellyfin.service"});
        let a = device("a", Scope::Operator);
        let b = device("b", Scope::Operator);

        // A is authorized; execution itself is reserved for 0.4.
        let err = authorized_exec(&path, &a, Op::ExecUnitRestart, &args).unwrap_err();
        assert_eq!(err.code, "not_implemented");
        assert_eq!(err.status, StatusCode::NOT_IMPLEMENTED);

        // B has the same token scope but no grant for that device.
        let err = authorized_exec(&path, &b, Op::ExecUnitRestart, &args).unwrap_err();
        assert_eq!(err.code, "capability_not_granted");
        assert_eq!(err.status, StatusCode::FORBIDDEN);

        // A unit outside the approved allow-list is denied, not approved.
        let err = authorized_exec(
            &path,
            &a,
            Op::ExecUnitRestart,
            &json!({"unit": "sshd.service"}),
        )
        .unwrap_err();
        assert_eq!(err.code, "denied");

        // Revoking A leaves B untouched.
        manifest.remove("a", "exec.unit_restart");
        manifest.save(&path).unwrap();
        let err = authorized_exec(&path, &a, Op::ExecUnitRestart, &args).unwrap_err();
        assert_eq!(err.code, "capability_not_granted");
        let _ = store;
        std::fs::remove_file(&path).ok();
    }

    #[test]
    fn ids_are_bounded() {
        assert!(valid_id("abc-123_X"));
        assert!(!valid_id(""));
        assert!(!valid_id("has space"));
        assert!(!valid_id(&"x".repeat(65)));
    }

    #[test]
    fn unit_names_reject_paths_and_wildcards() {
        assert!(valid_unit("jellyfin.service"));
        assert!(valid_unit("user@1000.service"));
        assert!(!valid_unit("*.service"));
        assert!(!valid_unit("../../etc/passwd"));
        assert!(!valid_unit("sshd"));
    }
}

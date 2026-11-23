use std::convert::Infallible;
use std::time::Duration;

use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::response::sse::{Event, KeepAlive, Sse};
use axum::response::IntoResponse;
use axum::Json;
use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tokio_stream::wrappers::BroadcastStream;

use super::AppState;
use crate::auth::{new_device_token, normalize_code, sha256_hex};
use crate::collect::{ProcRow, Sort};
use crate::store::{now_ts, Device};

const MAX_DEVICES: i64 = 16;
const MAX_DEVICE_NAME: usize = 64;

type ApiResult<T> = Result<Json<T>, (StatusCode, Json<Value>)>;

fn err(status: StatusCode, msg: &str) -> (StatusCode, Json<Value>) {
    (status, Json(json!({ "error": msg })))
}

pub async fn healthz(State(st): State<AppState>) -> impl IntoResponse {
    Json(json!({
        "ok": true,
        "version": env!("CARGO_PKG_VERSION"),
        "uptime_s": st.started.elapsed().as_secs(),
    }))
}

pub async fn host(State(st): State<AppState>) -> Json<Value> {
    Json(json!(st.host.as_ref()))
}

pub async fn metrics(State(st): State<AppState>) -> impl IntoResponse {
    let last = st.last.lock().map(|l| l.clone()).unwrap_or(None);
    match last {
        Some(sample) => Json(json!(*sample)).into_response(),
        None => (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(json!({ "error": "collector warming up" })),
        )
            .into_response(),
    }
}

pub async fn stream(
    State(st): State<AppState>,
) -> Sse<impl futures_util::Stream<Item = Result<Event, Infallible>> + Send + 'static> {
    let rx = st.tx.subscribe();
    let stream = BroadcastStream::new(rx).map(|item| {
        let event = match item {
            Ok(sample) => Event::default()
                .event("snapshot")
                .json_data(&*sample)
                .unwrap_or_else(|_| Event::default().event("error").data("{}")),
            // A slow client missed frames: tell it to refetch instead of
            // silently showing a gap.
            Err(_) => Event::default().event("resync").data("{}"),
        };
        Ok(event)
    });
    Sse::new(stream).keep_alive(KeepAlive::new().interval(Duration::from_secs(15)))
}

#[derive(Debug, Deserialize)]
pub struct HistoryQuery {
    pub range: Option<String>,
}

#[derive(Debug, Serialize)]
struct HistoryResponse {
    range: String,
    step: i64,
    from: i64,
    to: i64,
    points: Vec<crate::store::Point>,
}

/// Named ranges keep the client from asking for an unbounded scan.
pub fn plan_range(range: &str) -> Option<(i64, &'static str, i64)> {
    Some(match range {
        "15m" => (900, "samples_raw", 10),
        "1h" => (3_600, "samples_raw", 15),
        "6h" => (21_600, "samples_raw", 30),
        "24h" | "1d" => (86_400, "samples_1m", 60),
        "7d" | "1w" => (604_800, "samples_1m", 300),
        "30d" => (2_592_000, "samples_1m", 1_800),
        _ => return None,
    })
}

pub async fn history(
    State(st): State<AppState>,
    Query(q): Query<HistoryQuery>,
) -> impl IntoResponse {
    let range = q.range.unwrap_or_else(|| "1h".into());
    let Some((secs, table, step)) = plan_range(&range) else {
        return err(
            StatusCode::BAD_REQUEST,
            "invalid range (use 15m, 1h, 6h, 24h, 7d or 30d)",
        )
        .into_response();
    };
    let to = now_ts();
    let from = to - secs;
    match st.store.query(table, from, to, step) {
        Ok(points) => Json(HistoryResponse {
            range,
            step,
            from,
            to,
            points,
        })
        .into_response(),
        Err(e) => {
            tracing::error!(error = %e, "history query failed");
            err(StatusCode::INTERNAL_SERVER_ERROR, "query failed").into_response()
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct ProcQuery {
    pub sort: Option<String>,
    pub limit: Option<usize>,
}

pub async fn processes(
    State(st): State<AppState>,
    Query(q): Query<ProcQuery>,
) -> impl IntoResponse {
    let sort = match q.sort.as_deref().unwrap_or("cpu") {
        "mem" => Sort::Mem,
        "cpu" => Sort::Cpu,
        _ => return err(StatusCode::BAD_REQUEST, "sort must be cpu or mem").into_response(),
    };
    let limit = q.limit.unwrap_or(10);

    let sampler = st.procs.clone();
    let rows: Vec<ProcRow> = match tokio::task::spawn_blocking(move || {
        sampler
            .lock()
            .map(|mut s| s.snapshot(sort, limit))
            .unwrap_or_default()
    })
    .await
    {
        Ok(rows) => rows,
        Err(_) => {
            return err(StatusCode::INTERNAL_SERVER_ERROR, "process scan failed").into_response()
        }
    };

    Json(json!({ "sort": q.sort.unwrap_or_else(|| "cpu".into()), "processes": rows }))
        .into_response()
}

#[derive(Debug, Serialize)]
pub struct InfoResponse {
    name: String,
    version: String,
    pin: String,
    endpoints: Vec<String>,
    devices: Vec<Device>,
    /// Where the agent reads grants from; the CLI writes the same path.
    caps_manifest: String,
}

pub async fn info(State(st): State<AppState>) -> Json<InfoResponse> {
    Json(InfoResponse {
        name: st.host.hostname.clone(),
        version: env!("CARGO_PKG_VERSION").to_string(),
        pin: st.pin.as_ref().clone(),
        endpoints: st
            .endpoints
            .read()
            .map(|list| list.clone())
            .unwrap_or_default(),
        devices: st.store.list_devices(),
        caps_manifest: st.caps_manifest.display().to_string(),
    })
}

#[derive(Debug, Deserialize)]
pub struct AuditQuery {
    pub limit: Option<usize>,
    pub before: Option<i64>,
}

/// Operator only: the `require_operator` middleware already enforced the
/// scope, so a reader never gets here.
pub async fn audit(State(st): State<AppState>, Query(q): Query<AuditQuery>) -> Json<Value> {
    let entries = st.store.query_audit(q.limit.unwrap_or(100), q.before);
    Json(json!({
        "entries": entries,
        "retention_days": 90,
        "max_rows": 10_000,
    }))
}

#[derive(Debug, Deserialize)]
pub struct AlertsQuery {
    pub limit: Option<usize>,
    pub before: Option<i64>,
}

/// Operator only. Returns the rule set, the recent history and the current
/// disk predictions (used by the rules screen).
pub async fn alerts(State(st): State<AppState>, Query(q): Query<AlertsQuery>) -> Json<Value> {
    let now = now_ts();
    Json(json!({
        "rules": st.store.alert_rules_view(),
        "events": st.store.query_alert_events(q.limit.unwrap_or(50), q.before),
        "predictions": crate::alerts::predictions(&st.store, now),
        "retention_days": 30,
    }))
}

#[derive(Debug, Deserialize)]
pub struct PairRequest {
    pub code: String,
    #[serde(default)]
    pub device_name: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct PairResponse {
    pub device_id: String,
    pub token: String,
    pub scope: String,
}

pub async fn pair(
    State(st): State<AppState>,
    Json(req): Json<PairRequest>,
) -> ApiResult<PairResponse> {
    if st.store.count_devices() >= MAX_DEVICES {
        return Err(err(StatusCode::FORBIDDEN, "device limit reached"));
    }

    let code = normalize_code(&req.code);
    if code.len() != 8 {
        return Err(err(StatusCode::BAD_REQUEST, "malformed code"));
    }
    let Some(scope) = st.store.consume_pairing_code(&sha256_hex(&code)) else {
        return Err(err(StatusCode::UNAUTHORIZED, "invalid or expired code"));
    };

    let name: String = req
        .device_name
        .unwrap_or_default()
        .chars()
        .filter(|c| !c.is_control())
        .take(MAX_DEVICE_NAME)
        .collect();
    let name = if name.trim().is_empty() {
        "android".to_string()
    } else {
        name.trim().to_string()
    };

    let (device_id, token) = new_device_token();
    if let Err(e) = st
        .store
        .insert_device(&device_id, &name, &sha256_hex(&token), scope)
    {
        tracing::error!(error = %e, "cannot store device");
        return Err(err(
            StatusCode::INTERNAL_SERVER_ERROR,
            "cannot store device",
        ));
    }
    tracing::info!(device = %device_id, name = %name, scope = scope.as_str(), "device paired");
    Ok(Json(PairResponse {
        device_id,
        token,
        scope: scope.as_str().to_string(),
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ranges_are_bounded() {
        let (secs, table, step) = plan_range("1h").unwrap();
        assert_eq!(secs, 3600);
        assert_eq!(table, "samples_raw");
        assert_eq!(step, 15);

        let (secs, table, _) = plan_range("30d").unwrap();
        assert_eq!(secs, 2_592_000);
        assert_eq!(table, "samples_1m");

        assert!(plan_range("forever").is_none());
    }
}

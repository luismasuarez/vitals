pub mod handlers;

use std::sync::{Arc, Mutex};
use std::time::Instant;

use axum::middleware;
use axum::routing::{get, post};
use axum::Router;
use serde::Serialize;
use tokio::sync::broadcast;
use tower_http::limit::RequestBodyLimitLayer;
use tower_http::trace::TraceLayer;

use crate::auth::{self, RateLimiter};
use crate::collect::{ProcSampler, Sample};
use crate::store::Store;

/// Caps handler time for the buffered JSON endpoints. SSE is excluded
/// on purpose: a stream must be allowed to live indefinitely.
async fn request_timeout(
    req: axum::extract::Request,
    next: middleware::Next,
) -> axum::response::Response {
    use axum::response::IntoResponse;
    match tokio::time::timeout(std::time::Duration::from_secs(20), next.run(req)).await {
        Ok(resp) => resp,
        Err(_) => (axum::http::StatusCode::GATEWAY_TIMEOUT, "request timed out").into_response(),
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct HostInfo {
    pub hostname: String,
    pub os: String,
    pub kernel: String,
    pub arch: String,
    pub boot_time: u64,
    pub cpu_model: String,
    pub cores: usize,
    pub mem_total: u64,
}

#[derive(Clone)]
pub struct AppState {
    pub store: Arc<Store>,
    pub tx: broadcast::Sender<Arc<Sample>>,
    pub last: Arc<Mutex<Option<Arc<Sample>>>>,
    pub pin: Arc<String>,
    /// Recomputed whenever the local address set changes.
    pub endpoints: Arc<std::sync::RwLock<Vec<String>>>,
    pub host: Arc<HostInfo>,
    pub limiter: Arc<RateLimiter>,
    pub procs: Arc<Mutex<ProcSampler>>,
    pub started: Instant,
}

pub fn router(state: AppState) -> Router {
    // One endpoint without a token. Rate limited by IP.
    let pairing = Router::new()
        .route("/api/v1/pair", post(handlers::pair))
        .layer(middleware::from_fn_with_state(
            state.clone(),
            auth::pair_rate_limit,
        ));

    // Short request/response handlers: safe to cap total time.
    let metadata = Router::new()
        .route("/api/v1/host", get(handlers::host))
        .route("/api/v1/metrics", get(handlers::metrics))
        .route("/api/v1/history", get(handlers::history))
        .route("/api/v1/processes", get(handlers::processes))
        .route("/api/v1/info", get(handlers::info))
        .layer(middleware::from_fn_with_state(
            state.clone(),
            auth::require_token,
        ))
        .layer(middleware::from_fn(request_timeout));

    // Long-lived stream: no timeout layer here, or SSE dies every 20s.
    let stream = Router::new()
        .route("/api/v1/stream", get(handlers::stream))
        .layer(middleware::from_fn_with_state(
            state.clone(),
            auth::require_token,
        ));

    Router::new()
        .route("/healthz", get(handlers::healthz))
        .merge(pairing)
        .merge(metadata)
        .merge(stream)
        .layer(TraceLayer::new_for_http())
        .layer(RequestBodyLimitLayer::new(8 * 1024))
        .with_state(state)
}

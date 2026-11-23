pub mod command;
pub mod handlers;

use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Instant;

use axum::middleware;
use axum::routing::{get, post};
use axum::Router;
use serde::Serialize;
use tokio::sync::broadcast;
use tower_http::limit::RequestBodyLimitLayer;
use tower_http::trace::TraceLayer;

use crate::auth::{self, DeviceLimiter, RateLimiter};
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
    /// Per-device budget for `command`, independent of the client IP.
    pub cmd_limiter: Arc<DeviceLimiter>,
    pub procs: Arc<Mutex<ProcSampler>>,
    /// Root-owned manifest of capability grants. The agent only reads it.
    pub caps_manifest: Arc<PathBuf>,
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

    // The two operator-only routes. The 4 KB rule is enforced (and audited)
    // inside the handler; the global 8 KB layer caps anything larger.
    let operator = Router::new()
        .route("/api/v1/audit", get(handlers::audit))
        .route("/api/v1/command", post(command::command))
        .layer(middleware::from_fn(request_timeout))
        .layer(middleware::from_fn_with_state(
            state.clone(),
            auth::require_operator,
        ))
        .layer(middleware::from_fn_with_state(
            state.clone(),
            auth::require_token,
        ));

    Router::new()
        .route("/healthz", get(handlers::healthz))
        .merge(pairing)
        .merge(metadata)
        .merge(stream)
        .merge(operator)
        .layer(TraceLayer::new_for_http())
        .layer(RequestBodyLimitLayer::new(8 * 1024))
        .with_state(state)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth::{new_device_token, sha256_hex};
    use crate::caps::Scope;
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use tower::ServiceExt;

    fn test_state() -> AppState {
        let (tx, _) = broadcast::channel::<Arc<Sample>>(4);
        AppState {
            store: Arc::new(Store::in_memory()),
            tx,
            last: Arc::new(Mutex::new(None)),
            pin: Arc::new("test-pin".into()),
            endpoints: Arc::new(std::sync::RwLock::new(Vec::new())),
            host: Arc::new(HostInfo {
                hostname: "test".into(),
                os: "Linux".into(),
                kernel: "test".into(),
                arch: "x86_64".into(),
                boot_time: 0,
                cpu_model: "test".into(),
                cores: 1,
                mem_total: 1024,
            }),
            limiter: Arc::new(RateLimiter::default()),
            cmd_limiter: Arc::new(DeviceLimiter::default()),
            procs: Arc::new(Mutex::new(ProcSampler::new())),
            caps_manifest: Arc::new(PathBuf::from("/tmp/vitals-test-caps.toml")),
            started: Instant::now(),
        }
    }

    async fn status(app: &Router, method: &str, path: &str, token: Option<&str>) -> StatusCode {
        let mut builder = Request::builder().method(method).uri(path);
        if let Some(token) = token {
            builder = builder.header("authorization", format!("Bearer {token}"));
        }
        let req = builder.body(Body::empty()).unwrap();
        app.clone().oneshot(req).await.unwrap().status()
    }

    fn reader_token(state: &mut AppState) -> String {
        let (id, token) = new_device_token();
        state
            .store
            .insert_device(&id, "reader", &sha256_hex(&token), Scope::Reader)
            .unwrap();
        token
    }

    fn operator_token(state: &mut AppState) -> String {
        let (id, token) = new_device_token();
        state
            .store
            .insert_device(&id, "operator", &sha256_hex(&token), Scope::Operator)
            .unwrap();
        token
    }

    #[tokio::test]
    async fn only_pair_and_command_accept_post() {
        let mut state = test_state();
        let token = operator_token(&mut state);
        let app = router(state);
        for path in [
            "/healthz",
            "/api/v1/host",
            "/api/v1/metrics",
            "/api/v1/history",
            "/api/v1/processes",
            "/api/v1/info",
            "/api/v1/stream",
            "/api/v1/audit",
        ] {
            assert_eq!(
                status(&app, "POST", path, Some(&token)).await,
                StatusCode::METHOD_NOT_ALLOWED,
                "POST {path} must not be routed"
            );
        }
        // The two exceptions are routed; they fail later on validation or auth.
        assert_ne!(
            status(&app, "POST", "/api/v1/pair", None).await,
            StatusCode::METHOD_NOT_ALLOWED
        );
        assert_eq!(
            status(&app, "GET", "/api/v1/pair", None).await,
            StatusCode::METHOD_NOT_ALLOWED
        );
        assert_eq!(
            status(&app, "GET", "/api/v1/command", Some(&token)).await,
            StatusCode::METHOD_NOT_ALLOWED
        );
    }

    #[tokio::test]
    async fn reader_cannot_reach_operator_routes() {
        let mut state = test_state();
        let token = reader_token(&mut state);
        let app = router(state);

        assert_eq!(
            status(&app, "POST", "/api/v1/command", Some(&token)).await,
            StatusCode::FORBIDDEN
        );
        assert_eq!(
            status(&app, "GET", "/api/v1/audit", Some(&token)).await,
            StatusCode::FORBIDDEN
        );
        assert_eq!(
            status(&app, "GET", "/api/v1/metrics", Some(&token)).await,
            StatusCode::SERVICE_UNAVAILABLE // token accepted, collector empty
        );
    }

    #[tokio::test]
    async fn operator_routes_still_require_a_token() {
        let app = router(test_state());
        assert_eq!(
            status(&app, "POST", "/api/v1/command", None).await,
            StatusCode::UNAUTHORIZED
        );
        assert_eq!(
            status(&app, "GET", "/api/v1/audit", None).await,
            StatusCode::UNAUTHORIZED
        );
    }
}

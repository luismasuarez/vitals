use std::os::unix::fs::PermissionsExt;
use std::path::Path;

use anyhow::{bail, Context, Result};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{UnixListener, UnixStream};

use crate::api::AppState;
use crate::auth::{normalize_code, pairing_code, sha256_hex};
use crate::caps::{self, Manifest, Scope};
use crate::store::CapRequest;

const DEFAULT_TTL_SECS: u64 = 300;
const MAX_TTL_SECS: u64 = 900;

/// Admin channel. A unix socket in the runtime directory: root can connect,
/// nothing on the network can, and it disappears when the service stops.
pub async fn serve(paths: &crate::config::Paths, st: AppState) -> Result<()> {
    let _ = std::fs::remove_file(&paths.control_sock);
    let listener = UnixListener::bind(&paths.control_sock).with_context(|| {
        format!(
            "cannot bind control socket {}",
            paths.control_sock.display()
        )
    })?;
    let _ = std::fs::set_permissions(&paths.control_sock, PermissionsExt::from_mode(0o660));
    tracing::info!(path = %paths.control_sock.display(), "control socket ready");

    loop {
        let (sock, _) = listener.accept().await?;
        let st = st.clone();
        tokio::spawn(async move {
            if let Err(e) = handle(sock, st).await {
                tracing::warn!(error = %e, "control request failed");
            }
        });
    }
}

async fn handle(sock: UnixStream, st: AppState) -> Result<()> {
    let (rd, mut wr) = sock.into_split();
    let mut line = String::new();
    BufReader::new(rd).read_line(&mut line).await?;

    let req: Value = match serde_json::from_str(&line) {
        Ok(v) => v,
        Err(e) => {
            wr.write_all(
                format!("{}\n", json!({ "ok": false, "error": e.to_string() })).as_bytes(),
            )
            .await?;
            return Ok(());
        }
    };

    let reply = dispatch(&st, &req);
    wr.write_all(format!("{reply}\n").as_bytes()).await?;
    wr.flush().await?;
    Ok(())
}

fn dispatch(st: &AppState, req: &Value) -> Value {
    match req.get("cmd").and_then(Value::as_str) {
        Some("pair") => {
            // `--name` labels the device being paired, never the host: the
            // host identity in the payload must match /api/v1/host.
            let label = req
                .get("name")
                .and_then(Value::as_str)
                .map(str::to_string)
                .unwrap_or_else(|| "android".to_string());
            let ttl = req
                .get("ttl")
                .and_then(Value::as_u64)
                .unwrap_or(DEFAULT_TTL_SECS)
                .clamp(60, MAX_TTL_SECS);
            let scope = match req.get("scope").and_then(Value::as_str) {
                None => Scope::Reader,
                Some(value) => match Scope::parse(value) {
                    Some(scope) => scope,
                    None => {
                        return json!({ "ok": false, "error": format!("unknown scope: {value}") })
                    }
                },
            };

            let code = pairing_code();
            if let Err(e) =
                st.store
                    .add_pairing_code(&sha256_hex(&normalize_code(&code)), ttl, scope)
            {
                return json!({ "ok": false, "error": format!("cannot store pairing code: {e}") });
            }
            let exp = crate::store::now_ts() + ttl as i64;
            let payload = json!({
                "v": 1,
                "name": st.host.hostname.clone(),
                "dev": label,
                "scope": scope.as_str(),
                "ep": st
                    .endpoints
                    .read()
                    .map(|list| list.clone())
                    .unwrap_or_default(),
                "pin": st.pin.as_ref(),
                "code": code,
                "exp": exp,
            });
            let raw = serde_json::to_vec(&payload).unwrap_or_default();
            json!({
                "ok": true,
                "payload": payload,
                "qr": format!("vitals1:{}", URL_SAFE_NO_PAD.encode(raw)),
            })
        }
        Some("devices") => json!({ "ok": true, "devices": st.store.list_devices() }),
        Some("revoke") => {
            let Some(id) = req.get("id").and_then(Value::as_str) else {
                return json!({ "ok": false, "error": "missing id" });
            };
            let removed = st.store.revoke_device(id);
            json!({ "ok": true, "removed": removed })
        }
        Some("info") => json!({
            "ok": true,
            "name": st.host.hostname,
            "version": env!("CARGO_PKG_VERSION"),
            "pin": st.pin.as_ref(),
            "endpoints": st
                .endpoints
                .read()
                .map(|list| list.clone())
                .unwrap_or_default(),
            "devices": st.store.count_devices(),
            "caps_manifest": st.caps_manifest.display().to_string(),
        }),
        Some("caps_pending") => {
            let requests: Vec<Value> = st
                .store
                .pending_cap_requests()
                .iter()
                .map(request_json)
                .collect();
            json!({ "ok": true, "requests": requests })
        }
        Some("caps_lookup") => {
            let Some(code) = req.get("code").and_then(Value::as_str) else {
                return json!({ "ok": false, "error": "missing code" });
            };
            let code = normalize_code(code);
            if code.len() != 6 {
                return json!({ "ok": false, "error": "approval codes have 6 characters" });
            }
            match st.store.cap_request_by_code(&sha256_hex(&code)) {
                Some(request) => json!({ "ok": true, "request": request_json(&request) }),
                None => json!({ "ok": false, "error": "no pending request with that code" }),
            }
        }
        Some("caps_mark") => {
            let Some(id) = req.get("id").and_then(Value::as_i64) else {
                return json!({ "ok": false, "error": "missing id" });
            };
            let source = req.get("source").and_then(Value::as_str).unwrap_or("list");
            if source != "list" && source != "code" {
                return json!({ "ok": false, "error": "source must be list or code" });
            }
            json!({ "ok": true, "marked": st.store.approve_cap_request(id, source) })
        }
        Some("caps_grants") => {
            let manifest = Manifest::load(&st.caps_manifest).unwrap_or_else(|e| {
                tracing::warn!(error = %e, "cannot read capability manifest");
                Manifest::new()
            });
            let grants: Vec<Value> = manifest
                .grants
                .iter()
                .map(|grant| {
                    json!({
                        "device": grant.device,
                        "cap": grant.cap,
                        "risk": caps::risk_of(&grant.cap).map(|r| r.as_str()).unwrap_or("unknown"),
                        "params": grant.params.as_ref()
                            .map(|t| caps::toml_to_json(&toml::Value::Table(t.clone())))
                            .unwrap_or(Value::Null),
                        "approved_at": grant.approved_at,
                        "source": grant.source,
                    })
                })
                .collect();
            json!({
                "ok": true,
                "manifest": st.caps_manifest.display().to_string(),
                "grants": grants,
            })
        }
        Some(other) => json!({ "ok": false, "error": format!("unknown command: {other}") }),
        None => json!({ "ok": false, "error": "missing cmd" }),
    }
}

fn request_json(request: &CapRequest) -> Value {
    let params: Value = serde_json::from_str(&request.params).unwrap_or(Value::Null);
    json!({
        "id": request.id,
        "device_id": request.device_id,
        "device_name": request.device_name,
        "cap": request.cap,
        "risk": caps::risk_of(&request.cap).map(|r| r.as_str()).unwrap_or("unknown"),
        "params": params,
        "created_at": request.created_at,
        "expires_at": request.expires_at,
    })
}

/// Client side: one request, one reply, over the unix socket.
pub fn request(sock: &Path, req: &Value) -> Result<Value> {
    use std::io::{BufRead, BufReader as SyncReader, Write};
    use std::os::unix::net::UnixStream as SyncStream;

    let stream = SyncStream::connect(sock).with_context(|| {
        format!(
            "cannot reach {} — is the vitals service running?",
            sock.display()
        )
    })?;
    stream.set_read_timeout(Some(std::time::Duration::from_secs(10)))?;
    (&stream).write_all(format!("{req}\n").as_bytes())?;

    let mut line = String::new();
    SyncReader::new(stream).read_line(&mut line)?;
    serde_json::from_str(&line).context("malformed reply from vitals service")
}

/// Tries every known control socket. Used by the CLI so `vitals pair` works
/// from any directory without flags.
pub fn request_any(candidates: &[std::path::PathBuf], req: &Value) -> Result<Value> {
    let mut tried: Vec<String> = Vec::new();
    for path in candidates {
        if !path.exists() {
            continue;
        }
        match request(path, req) {
            Ok(reply) => return Ok(reply),
            Err(e) => tried.push(format!("{} ({e})", path.display())),
        }
    }

    if tried.is_empty() {
        bail!(
            "no encuentro el agente en ejecución.\n  Arranca el servicio con `systemctl --user start vitals`\n  o en primer plano con `vitals serve`.\n  Rutas buscadas: {}",
            candidates
                .iter()
                .map(|p| p.display().to_string())
                .collect::<Vec<_>>()
                .join(", ")
        );
    }
    bail!(
        "el agente no responde en ningún socket conocido:\n  {}",
        tried.join("\n  ")
    );
}

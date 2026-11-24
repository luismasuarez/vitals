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

            let code = pairing_code();
            if let Err(e) = st
                .store
                .add_pairing_code(&sha256_hex(&normalize_code(&code)), ttl)
            {
                return json!({ "ok": false, "error": format!("cannot store pairing code: {e}") });
            }
            let exp = crate::store::now_ts() + ttl as i64;
            let payload = json!({
                "v": 1,
                "name": st.host.hostname.clone(),
                "dev": label,
                "ep": st.endpoints.as_ref(),
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
            "endpoints": st.endpoints.as_ref(),
            "devices": st.store.count_devices(),
        }),
        Some(other) => json!({ "ok": false, "error": format!("unknown command: {other}") }),
        None => json!({ "ok": false, "error": "missing cmd" }),
    }
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

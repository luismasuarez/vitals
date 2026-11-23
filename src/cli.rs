use std::io::{IsTerminal, Write};
use std::path::PathBuf;

use anyhow::{bail, Result};
use qrcode::render::unicode::Dense1x2;
use qrcode::QrCode;
use serde_json::{json, Value};

use crate::caps::{self, Grant, Manifest};
use crate::control::request_any;
use crate::store::now_ts;

fn ok_or_error(reply: &Value) -> Result<()> {
    if reply.get("ok").and_then(Value::as_bool) == Some(true) {
        return Ok(());
    }
    bail!(
        "{}",
        reply
            .get("error")
            .and_then(Value::as_str)
            .unwrap_or("unknown error from vitals service")
    )
}

pub fn pair(sockets: &[PathBuf], name: Option<&str>, ttl: u64, operator: bool) -> Result<()> {
    let mut req = json!({ "cmd": "pair", "ttl": ttl });
    if let Some(n) = name {
        req["name"] = json!(n);
    }
    if operator {
        req["scope"] = json!("operator");
    }
    let reply = request_any(sockets, &req)?;
    ok_or_error(&reply)?;

    let payload = &reply["payload"];
    let qr_data = reply.get("qr").and_then(Value::as_str).unwrap_or_default();
    let endpoints: Vec<String> = payload["ep"]
        .as_array()
        .map(|a| {
            a.iter()
                .filter_map(Value::as_str)
                .map(str::to_string)
                .collect()
        })
        .unwrap_or_default();

    if endpoints.is_empty() {
        eprintln!(
            "warning: the agent is only listening on loopback; nothing on the network can reach it"
        );
    }

    let code = QrCode::new(qr_data.as_bytes())
        .map_err(|e| anyhow::anyhow!("payload too large to encode as QR: {e}"))?;
    let art = code.render::<Dense1x2>().quiet_zone(true).build();

    println!();
    println!("{art}");
    println!("  host       {}", payload["name"].as_str().unwrap_or("?"));
    println!("  dispositivo {}", payload["dev"].as_str().unwrap_or("?"));
    println!(
        "  scope      {}",
        payload["scope"].as_str().unwrap_or("reader")
    );
    println!(
        "  code       {}   (one use, expires in {}s)",
        payload["code"].as_str().unwrap_or("?"),
        (payload["exp"].as_i64().unwrap_or(0) - now_ts()).max(0)
    );
    for ep in &endpoints {
        println!("  endpoint   {ep}");
    }
    println!("  pin        {}", payload["pin"].as_str().unwrap_or("?"));
    println!();
    println!("  Scan from the app, or paste the string below if the camera struggles.");
    println!("  {qr_data}");
    println!();
    Ok(())
}

pub fn devices(sockets: &[PathBuf]) -> Result<()> {
    let reply = request_any(sockets, &json!({ "cmd": "devices" }))?;
    ok_or_error(&reply)?;
    let empty = Vec::new();
    let list = reply["devices"].as_array().unwrap_or(&empty);
    if list.is_empty() {
        println!("no paired devices");
        return Ok(());
    }
    println!("{:<14} {:<24} {:<12} LAST SEEN", "ID", "NAME", "CREATED");
    for d in list {
        println!(
            "{:<14} {:<24} {:<12} {}",
            d["id"].as_str().unwrap_or("-"),
            d["name"].as_str().unwrap_or("-"),
            fmt_ts(d["created_at"].as_i64().unwrap_or(0)),
            d["last_seen_at"]
                .as_i64()
                .map(fmt_ts)
                .unwrap_or_else(|| "never".into()),
        );
    }
    Ok(())
}

pub fn revoke(sockets: &[PathBuf], id: &str) -> Result<()> {
    let reply = request_any(sockets, &json!({ "cmd": "revoke", "id": id }))?;
    ok_or_error(&reply)?;
    if reply["removed"].as_bool() == Some(true) {
        println!("device {id} revoked");
        Ok(())
    } else {
        bail!("no device with id {id}")
    }
}

pub fn info(sockets: &[PathBuf]) -> Result<()> {
    let reply = request_any(sockets, &json!({ "cmd": "info" }))?;
    ok_or_error(&reply)?;
    println!("name       {}", reply["name"].as_str().unwrap_or("?"));
    println!("version    {}", reply["version"].as_str().unwrap_or("?"));
    println!("pin        {}", reply["pin"].as_str().unwrap_or("?"));
    println!("devices    {}", reply["devices"].as_i64().unwrap_or(0));
    println!(
        "caps       {}",
        reply["caps_manifest"].as_str().unwrap_or("?")
    );
    for ep in reply["endpoints"].as_array().cloned().unwrap_or_default() {
        println!("endpoint   {}", ep.as_str().unwrap_or("?"));
    }
    Ok(())
}

// --- capability grants -----------------------------------------------------

pub fn caps_list(sockets: &[PathBuf]) -> Result<()> {
    let reply = request_any(sockets, &json!({ "cmd": "caps_grants" }))?;
    ok_or_error(&reply)?;
    let empty = Vec::new();
    let grants = reply["grants"].as_array().unwrap_or(&empty);
    println!("manifest   {}", reply["manifest"].as_str().unwrap_or("?"));
    if grants.is_empty() {
        println!("no capability grants");
        return Ok(());
    }
    println!(
        "{:<14} {:<24} {:<7} {:<12} PARAMS",
        "DEVICE", "CAPABILITY", "RISK", "APPROVED"
    );
    for grant in grants {
        println!(
            "{:<14} {:<24} {:<7} {:<12} {}",
            grant["device"].as_str().unwrap_or("-"),
            grant["cap"].as_str().unwrap_or("-"),
            grant["risk"].as_str().unwrap_or("-"),
            fmt_ts(grant["approved_at"].as_i64().unwrap_or(0)),
            compact(&grant["params"]),
        );
    }
    Ok(())
}

pub fn caps_pending(sockets: &[PathBuf]) -> Result<()> {
    let reply = request_any(sockets, &json!({ "cmd": "caps_pending" }))?;
    ok_or_error(&reply)?;
    let empty = Vec::new();
    let requests = reply["requests"].as_array().unwrap_or(&empty);
    if requests.is_empty() {
        println!("no pending capability requests");
        return Ok(());
    }
    println!(
        "{:<4} {:<14} {:<24} {:<7} {:<8} PARAMS",
        "#", "DEVICE", "CAPABILITY", "RISK", "EXPIRES"
    );
    for (i, request) in requests.iter().enumerate() {
        println!(
            "{:<4} {:<14} {:<24} {:<7} {:<8} {}",
            i + 1,
            request["device_id"].as_str().unwrap_or("-"),
            request["cap"].as_str().unwrap_or("-"),
            request["risk"].as_str().unwrap_or("-"),
            fmt_left(request["expires_at"].as_i64().unwrap_or(0)),
            compact(&request["params"]),
        );
    }
    println!();
    println!("approve one with: vitals caps approve <#>   (or the 6-char code)");
    Ok(())
}

pub fn caps_approve(sockets: &[PathBuf], target: &str, yes: bool) -> Result<()> {
    let (request, source) = if target.chars().all(|c| c.is_ascii_digit()) {
        let index: usize = target
            .parse()
            .map_err(|_| anyhow::anyhow!("invalid request number: {target}"))?;
        let reply = request_any(sockets, &json!({ "cmd": "caps_pending" }))?;
        ok_or_error(&reply)?;
        let empty = Vec::new();
        let requests = reply["requests"].as_array().unwrap_or(&empty);
        if index == 0 || index > requests.len() {
            bail!(
                "no request #{index}; {} pending (see `vitals caps pending`)",
                requests.len()
            );
        }
        (requests[index - 1].clone(), "list")
    } else {
        let reply = request_any(sockets, &json!({ "cmd": "caps_lookup", "code": target }))?;
        ok_or_error(&reply)?;
        (reply["request"].clone(), "code")
    };

    let id = request["id"]
        .as_i64()
        .ok_or_else(|| anyhow::anyhow!("malformed request from the agent"))?;
    let device = request["device_id"].as_str().unwrap_or("?");
    let cap = request["cap"].as_str().unwrap_or("?");
    let risk = request["risk"].as_str().unwrap_or("unknown");

    println!("request #{id} -> {source}");
    println!(
        "  device   {} ({device})",
        request["device_name"].as_str().unwrap_or("?")
    );
    println!("  capability {cap} (risk {risk})");
    println!("  params   {}", compact(&request["params"]));
    println!(
        "  expires  {}",
        fmt_left(request["expires_at"].as_i64().unwrap_or(0))
    );

    if !caps::is_known(cap) {
        bail!("the agent reported an unknown capability: {cap}");
    }
    if !confirm("approve this grant?", yes)? {
        bail!("cancelled");
    }

    let path = caps_manifest(sockets)?;
    let mut manifest = Manifest::load(&path)?;
    let params = request
        .get("params")
        .filter(|value| value.is_object())
        .and_then(caps::json_to_toml)
        .and_then(|value| value.as_table().cloned());
    manifest.upsert(Grant::new(device, cap, params, source))?;
    manifest.save(&path)?;

    let marked = request_any(
        sockets,
        &json!({ "cmd": "caps_mark", "id": id, "source": source }),
    )?;
    ok_or_error(&marked)?;
    if marked["marked"].as_bool() != Some(true) {
        println!("warning: grant written but the request was no longer pending");
    }
    println!("approved {cap} for {device} in {}", path.display());
    Ok(())
}

pub fn caps_revoke(sockets: &[PathBuf], device: &str, cap: &str) -> Result<()> {
    let path = caps_manifest(sockets)?;
    let mut manifest = Manifest::load(&path)?;
    if !manifest.remove(device, cap) {
        bail!("no grant of {cap} for {device} (see `vitals caps list`)");
    }
    manifest.save(&path)?;
    println!("revoked {cap} for {device}");
    Ok(())
}

fn caps_manifest(sockets: &[PathBuf]) -> Result<PathBuf> {
    let reply = request_any(sockets, &json!({ "cmd": "info" }))?;
    ok_or_error(&reply)?;
    let path = reply["caps_manifest"]
        .as_str()
        .ok_or_else(|| anyhow::anyhow!("the agent did not report a manifest path"))?;
    Ok(PathBuf::from(path))
}

fn confirm(prompt: &str, yes: bool) -> Result<bool> {
    if yes || !std::io::stdin().is_terminal() {
        return Ok(true);
    }
    print!("{prompt} [y/N] ");
    std::io::stdout().flush()?;
    let mut line = String::new();
    std::io::stdin().read_line(&mut line)?;
    Ok(matches!(line.trim(), "y" | "Y" | "yes" | "YES"))
}

fn compact(value: &Value) -> String {
    match value {
        Value::Null => "-".into(),
        other => other.to_string(),
    }
}

fn fmt_left(ts: i64) -> String {
    let left = (ts - now_ts()).max(0);
    if left < 60 {
        format!("{left}s")
    } else if left < 3600 {
        format!("{}m", left / 60)
    } else {
        format!("{}h", left / 3600)
    }
}

fn fmt_ts(ts: i64) -> String {
    let age = (now_ts() - ts).max(0);
    if age < 60 {
        format!("{age}s ago")
    } else if age < 3600 {
        format!("{}m ago", age / 60)
    } else if age < 86_400 {
        format!("{}h ago", age / 3600)
    } else {
        format!("{}d ago", age / 86_400)
    }
}

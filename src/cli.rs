use std::path::Path;

use anyhow::{bail, Result};
use qrcode::render::unicode::Dense1x2;
use qrcode::QrCode;
use serde_json::{json, Value};

use crate::control::request;
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

pub fn pair(sock: &Path, name: Option<&str>, ttl: u64) -> Result<()> {
    let mut req = json!({ "cmd": "pair", "ttl": ttl });
    if let Some(n) = name {
        req["name"] = json!(n);
    }
    let reply = request(sock, &req)?;
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

pub fn devices(sock: &Path) -> Result<()> {
    let reply = request(sock, &json!({ "cmd": "devices" }))?;
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

pub fn revoke(sock: &Path, id: &str) -> Result<()> {
    let reply = request(sock, &json!({ "cmd": "revoke", "id": id }))?;
    ok_or_error(&reply)?;
    if reply["removed"].as_bool() == Some(true) {
        println!("device {id} revoked");
        Ok(())
    } else {
        bail!("no device with id {id}")
    }
}

pub fn info(sock: &Path) -> Result<()> {
    let reply = request(sock, &json!({ "cmd": "info" }))?;
    ok_or_error(&reply)?;
    println!("name       {}", reply["name"].as_str().unwrap_or("?"));
    println!("version    {}", reply["version"].as_str().unwrap_or("?"));
    println!("pin        {}", reply["pin"].as_str().unwrap_or("?"));
    println!("devices    {}", reply["devices"].as_i64().unwrap_or(0));
    for ep in reply["endpoints"].as_array().cloned().unwrap_or_default() {
        println!("endpoint   {}", ep.as_str().unwrap_or("?"));
    }
    Ok(())
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

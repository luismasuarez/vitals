use std::fs;
use std::io::BufReader;
use std::net::IpAddr;
use std::path::Path;

use anyhow::{Context, Result};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use rcgen::{CertificateParams, DnType, KeyPair, SanType};
use sha2::{Digest, Sha256};

use crate::config::Paths;

/// Length of the truncated certificate hash that ends up inside the QR code.
/// 128 bits of SHA-256 is more than enough against a second-preimage search,
/// and keeps the payload small enough for a low-density QR.
const PIN_BYTES: usize = 16;

/// Creates the self-signed certificate on first run. Returns true when the
/// certificate was generated (i.e. no pairing exists yet).
pub fn ensure(paths: &Paths, dns: &[String], ips: &[IpAddr]) -> Result<bool> {
    if paths.cert.exists() && paths.key.exists() {
        return Ok(false);
    }
    generate(paths, dns, ips)?;
    Ok(true)
}

fn generate(paths: &Paths, dns: &[String], ips: &[IpAddr]) -> Result<()> {
    let mut params =
        CertificateParams::new(dns.to_vec()).context("invalid subject alternative names")?;
    params.distinguished_name.push(
        DnType::CommonName,
        dns.first().cloned().unwrap_or_else(|| "vitals".into()),
    );
    for ip in ips {
        params.subject_alt_names.push(SanType::IpAddress(*ip));
    }
    // Validity is irrelevant for a pinned certificate: the client trusts the
    // hash it got during pairing, not a CA chain or a date window.
    let key = KeyPair::generate().context("cannot generate certificate key")?;
    let cert = params
        .self_signed(&key)
        .context("cannot self-sign certificate")?;

    write_private(&paths.key, key.serialize_pem().as_bytes())?;
    write_private(&paths.cert, cert.pem().as_bytes())?;
    Ok(())
}

fn write_private(path: &Path, bytes: &[u8]) -> Result<()> {
    use std::os::unix::fs::OpenOptionsExt;
    let mut f = fs::OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(0o600)
        .open(path)
        .with_context(|| format!("cannot write {}", path.display()))?;
    use std::io::Write;
    f.write_all(bytes)?;
    f.sync_all()?;
    Ok(())
}

pub fn cert_der(path: &Path) -> Result<Vec<u8>> {
    let file = fs::File::open(path).with_context(|| format!("cannot read {}", path.display()))?;
    let mut reader = BufReader::new(file);
    let first = rustls_pemfile::certs(&mut reader)
        .next()
        .transpose()?
        .context("certificate file contains no PEM certificate")?;
    Ok(first.to_vec())
}

/// Truncated SHA-256 of the leaf certificate, base64url without padding.
pub fn pin(paths: &Paths) -> Result<String> {
    let der = cert_der(&paths.cert)?;
    let digest = Sha256::digest(&der);
    Ok(URL_SAFE_NO_PAD.encode(&digest[..PIN_BYTES]))
}

use std::fs;
use std::net::IpAddr;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result};
use serde::Deserialize;

/// How the agent decides which local addresses to listen on.
#[derive(Debug, Clone, Copy, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "lowercase")]
pub enum BindMode {
    /// Loopback, Tailscale and private (RFC1918/ULA) addresses only.
    #[default]
    Auto,
    /// Loopback plus every non-loopback address, public ones included.
    All,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct Config {
    pub port: u16,
    pub bind_mode: BindMode,
    /// Opt-in to binding on public addresses. Ignored when bind_mode = "all".
    pub allow_public: bool,
    pub sample_interval_ms: u64,
    /// Persist one history row every N samples.
    pub persist_every: u32,
    pub retention_raw_hours: u32,
    pub retention_1m_days: u32,
    /// Extra addresses to listen on, e.g. a stable LAN IP outside the detected set.
    pub extra_bind_addrs: Vec<IpAddr>,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            port: 8443,
            bind_mode: BindMode::Auto,
            allow_public: false,
            sample_interval_ms: 1000,
            persist_every: 10,
            retention_raw_hours: 24,
            retention_1m_days: 30,
            extra_bind_addrs: Vec::new(),
        }
    }
}

impl Config {
    pub fn load(path: &Path) -> Result<Self> {
        if !path.exists() {
            return Ok(Self::default());
        }
        let raw = fs::read_to_string(path)
            .with_context(|| format!("cannot read config at {}", path.display()))?;
        let cfg: Config = toml::from_str(&raw)
            .with_context(|| format!("invalid config at {}", path.display()))?;
        Ok(cfg)
    }
}

/// Filesystem layout. Under systemd these come from STATE_DIRECTORY and
/// RUNTIME_DIRECTORY so the unit can run with DynamicUser=yes.
#[derive(Debug, Clone)]
pub struct Paths {
    pub state_dir: PathBuf,
    pub db: PathBuf,
    pub cert: PathBuf,
    pub key: PathBuf,
    pub control_sock: PathBuf,
}

impl Paths {
    pub fn resolve(state_dir: Option<&Path>) -> Result<Self> {
        // Dev runs pass --state-dir and keep everything in one place. Under
        // systemd, STATE_DIRECTORY and RUNTIME_DIRECTORY are separate, so the
        // control socket lives on tmpfs and is owned by the dynamic user.
        let (state_dir, runtime_dir) = match state_dir {
            Some(p) => (p.to_path_buf(), p.to_path_buf()),
            None => (
                std::env::var_os("STATE_DIRECTORY")
                    .map(PathBuf::from)
                    .unwrap_or_else(|| PathBuf::from("/var/lib/vitals")),
                std::env::var_os("RUNTIME_DIRECTORY")
                    .map(PathBuf::from)
                    .unwrap_or_else(|| PathBuf::from("/run/vitals")),
            ),
        };
        fs::create_dir_all(&state_dir)
            .with_context(|| format!("cannot create state dir {}", state_dir.display()))?;
        // The CLI only needs to read this path; the service creates it.
        fs::create_dir_all(&runtime_dir).ok();

        Ok(Self {
            db: state_dir.join("vitals.db"),
            cert: state_dir.join("tls-cert.pem"),
            key: state_dir.join("tls-key.pem"),
            control_sock: runtime_dir.join("control.sock"),
            state_dir,
        })
    }
}

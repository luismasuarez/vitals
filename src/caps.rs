//! Capability catalog, device scopes and the root-owned manifest.
//!
//! The manifest is the only place where a high-risk permission can be granted,
//! and only `vitals caps` writes it. The agent merely reads it for every
//! command, so a corrupt or missing file fails closed: no grants.

use std::fs::{self, OpenOptions};
use std::io::Write;
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::Path;

use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};

use crate::store::now_ts;

/// What a device token can do before any host-side approval.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Scope {
    /// GET of metrics, processes and history.
    Reader,
    /// Reader plus `command` and the audit log.
    Operator,
}

impl Scope {
    pub fn as_str(self) -> &'static str {
        match self {
            Scope::Reader => "reader",
            Scope::Operator => "operator",
        }
    }

    pub fn parse(value: &str) -> Option<Self> {
        match value {
            "reader" => Some(Scope::Reader),
            "operator" => Some(Scope::Operator),
            _ => None,
        }
    }

    /// Capabilities the scope carries without a manifest entry.
    pub fn caps(self) -> &'static [&'static str] {
        const READER: &[&str] = &["read.metrics", "read.processes", "read.history"];
        const OPERATOR: &[&str] = &[
            "read.metrics",
            "read.processes",
            "read.history",
            "read.audit",
            "write.alerts",
        ];
        match self {
            Scope::Reader => READER,
            Scope::Operator => OPERATOR,
        }
    }

    pub fn allows(self, cap: &str) -> bool {
        self.caps().contains(&cap)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Risk {
    Low,
    Medium,
    High,
}

impl Risk {
    pub fn as_str(self) -> &'static str {
        match self {
            Risk::Low => "low",
            Risk::Medium => "medium",
            Risk::High => "high",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Capability {
    pub id: &'static str,
    pub risk: Risk,
    /// Only approvable capabilities need a manifest entry.
    pub approvable: bool,
}

pub const CATALOG: &[Capability] = &[
    Capability {
        id: "read.metrics",
        risk: Risk::Low,
        approvable: false,
    },
    Capability {
        id: "read.processes",
        risk: Risk::Low,
        approvable: false,
    },
    Capability {
        id: "read.history",
        risk: Risk::Low,
        approvable: false,
    },
    Capability {
        id: "read.audit",
        risk: Risk::Low,
        approvable: false,
    },
    Capability {
        id: "write.alerts",
        risk: Risk::Low,
        approvable: false,
    },
    Capability {
        id: "write.channels",
        risk: Risk::Medium,
        approvable: false,
    },
    Capability {
        id: "exec.unit_restart",
        risk: Risk::High,
        approvable: true,
    },
    Capability {
        id: "exec.journal_vacuum",
        risk: Risk::Medium,
        approvable: true,
    },
    Capability {
        id: "exec.pkg_cache_clean",
        risk: Risk::Medium,
        approvable: true,
    },
];

pub fn capability(id: &str) -> Option<&'static Capability> {
    CATALOG.iter().find(|cap| cap.id == id)
}

pub fn is_known(id: &str) -> bool {
    capability(id).is_some()
}

pub fn is_approvable(id: &str) -> bool {
    capability(id).is_some_and(|cap| cap.approvable)
}

pub fn risk_of(id: &str) -> Option<Risk> {
    capability(id).map(|cap| cap.risk)
}

/// One approved capability, tied to a device. `params` narrows what the grant
/// allows: a unit allow-list or a maximum size. Absent params mean "nothing",
/// never "everything".
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Grant {
    pub device: String,
    pub cap: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub params: Option<toml::Table>,
    pub approved_at: i64,
    /// `list` or `code`: which approval path produced this row.
    pub source: String,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Manifest {
    #[serde(default = "current_version")]
    pub version: u32,
    #[serde(default, rename = "grant")]
    pub grants: Vec<Grant>,
}

fn current_version() -> u32 {
    1
}

impl Manifest {
    pub fn new() -> Self {
        Self {
            version: current_version(),
            grants: Vec::new(),
        }
    }

    /// Missing file means no grants. A malformed file is an error: the caller
    /// decides whether to fail closed (agent) or stop (CLI).
    pub fn load(path: &Path) -> Result<Self> {
        let raw = match fs::read_to_string(path) {
            Ok(raw) => raw,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Self::new()),
            Err(e) => return Err(e).with_context(|| format!("cannot read {}", path.display())),
        };
        let manifest: Manifest =
            toml::from_str(&raw).with_context(|| format!("invalid {}", path.display()))?;
        if manifest.version != current_version() {
            bail!(
                "unsupported manifest version {} in {}",
                manifest.version,
                path.display()
            );
        }
        Ok(manifest)
    }

    /// Read-only view used by the agent: any error yields an empty manifest.
    pub fn load_or_empty(path: &Path) -> Self {
        Self::load(path).unwrap_or_else(|e| {
            tracing::warn!(error = %e, "capability manifest ignored");
            Self::new()
        })
    }

    pub fn save(&self, path: &Path) -> Result<()> {
        if let Some(dir) = path.parent() {
            fs::create_dir_all(dir).with_context(|| format!("cannot create {}", dir.display()))?;
        }
        let body = toml::to_string_pretty(self).context("cannot serialize manifest")?;
        let tmp = path.with_extension(format!("tmp.{}", std::process::id()));
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o644)
            .open(&tmp)
            .with_context(|| format!("cannot create {}", tmp.display()))?;
        file.write_all(body.as_bytes())?;
        file.sync_all()?;
        drop(file);
        // Explicit chmod: umask must not make the manifest unreadable for the
        // DynamicUser agent on a system install.
        fs::set_permissions(&tmp, PermissionsExt::from_mode(0o644))?;
        fs::rename(&tmp, path).with_context(|| format!("cannot replace {}", path.display()))?;
        Ok(())
    }

    pub fn grant_for(&self, device: &str, cap: &str) -> Option<&Grant> {
        self.grants
            .iter()
            .find(|grant| grant.device == device && grant.cap == cap)
    }

    /// A grant can only be added if the capability exists in the catalog.
    pub fn upsert(&mut self, grant: Grant) -> Result<()> {
        if !is_known(&grant.cap) {
            bail!("unknown capability: {}", grant.cap);
        }
        self.grants
            .retain(|g| !(g.device == grant.device && g.cap == grant.cap));
        self.grants.push(grant);
        self.grants.sort_by(|a, b| {
            a.device
                .cmp(&b.device)
                .then_with(|| a.cap.cmp(&b.cap))
                .then_with(|| a.approved_at.cmp(&b.approved_at))
        });
        Ok(())
    }

    pub fn remove(&mut self, device: &str, cap: &str) -> bool {
        let before = self.grants.len();
        self.grants
            .retain(|g| !(g.device == device && g.cap == cap));
        self.grants.len() != before
    }
}

/// Single authorization rule: scope capabilities come for free; approvable
/// ones also need an operator token and a manifest row for this device.
pub fn is_allowed(manifest: &Manifest, device: &str, scope: Scope, cap: &str) -> bool {
    match capability(cap) {
        Some(capability) if capability.approvable => {
            scope == Scope::Operator && manifest.grant_for(device, cap).is_some()
        }
        Some(_) => scope.allows(cap),
        None => false,
    }
}

impl Grant {
    pub fn new(device: &str, cap: &str, params: Option<toml::Table>, source: &str) -> Self {
        Self {
            device: device.to_string(),
            cap: cap.to_string(),
            params,
            approved_at: now_ts(),
            source: source.to_string(),
        }
    }

    /// Narrow parameters decide whether a concrete request fits the grant.
    pub fn permits_unit(&self, unit: &str) -> bool {
        self.params
            .as_ref()
            .and_then(|params| params.get("units"))
            .and_then(toml::Value::as_array)
            .is_some_and(|units| units.iter().any(|u| u.as_str() == Some(unit)))
    }

    pub fn permits_max_bytes(&self, requested: u64) -> bool {
        self.params
            .as_ref()
            .and_then(|params| params.get("max_bytes"))
            .and_then(toml::Value::as_integer)
            .is_some_and(|max| max >= 0 && requested <= max as u64)
    }
}

/// Converts the JSON `params` accepted by `caps.request` into the TOML table
/// stored in the manifest. Only the value shapes the catalog allows survive.
pub fn json_to_toml(value: &serde_json::Value) -> Option<toml::Value> {
    match value {
        serde_json::Value::Null => None,
        serde_json::Value::Bool(b) => Some(toml::Value::Boolean(*b)),
        serde_json::Value::Number(n) => {
            if let Some(i) = n.as_i64() {
                Some(toml::Value::Integer(i))
            } else {
                n.as_f64().map(toml::Value::Float)
            }
        }
        serde_json::Value::String(s) => Some(toml::Value::String(s.clone())),
        serde_json::Value::Array(items) => {
            let mut out = Vec::with_capacity(items.len());
            for item in items {
                out.push(json_to_toml(item)?);
            }
            Some(toml::Value::Array(out))
        }
        serde_json::Value::Object(map) => {
            let mut table = toml::Table::new();
            for (key, item) in map {
                table.insert(key.clone(), json_to_toml(item)?);
            }
            Some(toml::Value::Table(table))
        }
    }
}

pub fn toml_to_json(value: &toml::Value) -> serde_json::Value {
    match value {
        toml::Value::String(s) => serde_json::Value::String(s.clone()),
        toml::Value::Integer(i) => serde_json::Value::from(*i),
        toml::Value::Float(f) => serde_json::Value::from(*f),
        toml::Value::Boolean(b) => serde_json::Value::Bool(*b),
        toml::Value::Datetime(dt) => serde_json::Value::String(dt.to_string()),
        toml::Value::Array(items) => {
            serde_json::Value::Array(items.iter().map(toml_to_json).collect())
        }
        toml::Value::Table(table) => serde_json::Value::Object(
            table
                .iter()
                .map(|(k, v)| (k.clone(), toml_to_json(v)))
                .collect(),
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir() -> std::path::PathBuf {
        use std::sync::atomic::{AtomicU64, Ordering};
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let dir = std::env::temp_dir().join(format!(
            "vitals-caps-{}-{}-{}",
            std::process::id(),
            now_ts(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn scopes_carry_the_documented_capabilities() {
        assert!(!Scope::Reader.allows("read.audit"));
        assert!(Scope::Reader.allows("read.metrics"));
        assert!(Scope::Operator.allows("read.audit"));
        assert!(Scope::Operator.allows("write.alerts"));
        assert!(!Scope::Operator.allows("exec.unit_restart"));
    }

    #[test]
    fn catalog_is_closed_and_risks_are_known() {
        assert_eq!(risk_of("exec.unit_restart"), Some(Risk::High));
        assert!(is_approvable("exec.journal_vacuum"));
        assert!(!is_approvable("write.alerts"));
        assert!(!is_known("exec.run_shell"));
    }

    #[test]
    fn manifest_roundtrips_and_grant_is_per_device() {
        let dir = temp_dir();
        let path = dir.join("capabilities.toml");
        let mut manifest = Manifest::new();
        let mut params = toml::Table::new();
        params.insert(
            "units".into(),
            toml::Value::Array(vec![toml::Value::String("jellyfin.service".into())]),
        );
        manifest
            .upsert(Grant::new(
                "device-a",
                "exec.unit_restart",
                Some(params),
                "list",
            ))
            .unwrap();
        manifest.save(&path).unwrap();

        let loaded = Manifest::load(&path).unwrap();
        let grant = loaded.grant_for("device-a", "exec.unit_restart").unwrap();
        assert!(grant.permits_unit("jellyfin.service"));
        assert!(!grant.permits_unit("sshd.service"));
        assert!(loaded.grant_for("device-b", "exec.unit_restart").is_none());

        assert!(is_allowed(
            &loaded,
            "device-a",
            Scope::Operator,
            "exec.unit_restart"
        ));
        assert!(!is_allowed(
            &loaded,
            "device-b",
            Scope::Operator,
            "exec.unit_restart"
        ));
        // A reader never inherits manifest grants.
        assert!(!is_allowed(
            &loaded,
            "device-a",
            Scope::Reader,
            "exec.unit_restart"
        ));
        assert!(is_allowed(
            &loaded,
            "device-a",
            Scope::Operator,
            "write.alerts"
        ));
        assert!(!is_allowed(
            &loaded,
            "device-a",
            Scope::Reader,
            "write.alerts"
        ));

        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn missing_manifest_grants_nothing_and_revoke_is_per_device() {
        let dir = temp_dir();
        let path = dir.join("capabilities.toml");
        let mut manifest = Manifest::load(&path).unwrap();
        assert!(manifest.grants.is_empty());

        manifest
            .upsert(Grant::new("a", "exec.pkg_cache_clean", None, "code"))
            .unwrap();
        manifest
            .upsert(Grant::new("b", "exec.pkg_cache_clean", None, "code"))
            .unwrap();
        assert!(manifest.remove("a", "exec.pkg_cache_clean"));
        assert!(manifest.grant_for("b", "exec.pkg_cache_clean").is_some());

        let mut bad = Grant::new("c", "exec.run_shell", None, "list");
        bad.cap = "exec.run_shell".into();
        assert!(manifest.upsert(bad).is_err());

        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn manifest_with_unknown_fields_fails_closed() {
        let dir = temp_dir();
        let path = dir.join("capabilities.toml");
        fs::write(&path, "version = 1\nextra = true\n").unwrap();
        assert!(Manifest::load(&path).is_err());
        assert!(Manifest::load_or_empty(&path).grants.is_empty());
        fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn json_params_survive_the_toml_roundtrip() {
        let json = serde_json::json!({
            "units": ["jellyfin.service", "nginx.service"],
            "max_bytes": 1024,
            "dry_run": true
        });
        let toml_value = json_to_toml(&json).unwrap();
        let table = toml_value.as_table().unwrap();
        let mut grant = Grant::new("d", "exec.unit_restart", Some(table.clone()), "list");
        assert!(grant.permits_unit("nginx.service"));
        grant.cap = "exec.journal_vacuum".into();
        assert!(grant.permits_max_bytes(1024));
        assert!(!grant.permits_max_bytes(1025));
    }
}

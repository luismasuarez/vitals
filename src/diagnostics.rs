//! Structured host findings and the exact command that would address each one.
//!
//! Nothing here executes anything. The goal is to turn what the agent can
//! observe (metrics, network, state directory) into a short, copy-pasteable
//! remediation list for the person holding the phone.

use std::path::Path;

use serde::Serialize;
use serde_json::{json, Value};

use crate::alerts::Prediction;
use crate::api::HostInfo;
use crate::collect::Sample;

#[derive(Debug, Clone, Serialize)]
pub struct Finding {
    pub id: String,
    pub severity: String,
    pub title: String,
    pub detail: String,
    pub evidence: Value,
    pub suggestion: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct Suggestion {
    pub id: String,
    pub finding: String,
    pub risk: String,
    pub command: String,
    pub explanation: String,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct Report {
    pub findings: Vec<Finding>,
    pub suggestions: Vec<Suggestion>,
}

impl Report {
    fn add(&mut self, finding: Finding, suggestion: Option<Suggestion>) {
        self.findings.push(finding);
        if let Some(suggestion) = suggestion {
            self.suggestions.push(suggestion);
        }
    }
}

fn severity(value: f64) -> (&'static str, &'static str) {
    if value >= 90.0 {
        ("critical", "crítico")
    } else {
        ("warning", "aviso")
    }
}

pub fn report(
    host: &HostInfo,
    sample: Option<&Sample>,
    endpoints: &[String],
    rules: usize,
    predictions: &[Prediction],
    state_dir: &Path,
) -> Report {
    let mut report = Report::default();
    let Some(sample) = sample else {
        report.findings.push(Finding {
            id: "collector.warming".into(),
            severity: "info".into(),
            title: "El colector está arrancando".into(),
            detail: "Todavía no hay una muestra completa para diagnosticar.".into(),
            evidence: json!({}),
            suggestion: None,
        });
        return report;
    };

    // Disk pressure: only the worst mount, to keep the list short.
    let worst_disk = sample
        .disks
        .iter()
        .filter(|disk| disk.total > 0)
        .map(|disk| {
            (
                disk.mount.clone(),
                disk.used as f64 / disk.total as f64 * 100.0,
                disk.used,
                disk.total,
            )
        })
        .max_by(|a, b| a.1.total_cmp(&b.1));
    if let Some((mount, used_pct, used, total)) = worst_disk {
        if used_pct >= 80.0 {
            let (level, _) = severity(used_pct);
            report.add(
                Finding {
                    id: format!("disk.pressure:{mount}"),
                    severity: level.into(),
                    title: format!("Disco {mount} al {used_pct:.1} %"),
                    detail: format!(
                        "Quedan {} de {}. Un disco lleno rompe bases de datos, logs y actualizaciones.",
                        bytes(total.saturating_sub(used)),
                        bytes(total)
                    ),
                    evidence: json!({
                        "mount": mount,
                        "used_pct": used_pct,
                        "used_bytes": used,
                        "total_bytes": total,
                    }),
                    suggestion: Some(format!("disk.free_space:{mount}")),
                },
                Some(Suggestion {
                    id: format!("disk.free_space:{mount}"),
                    finding: format!("disk.pressure:{mount}"),
                    risk: "low".into(),
                    command: "sudo journalctl --vacuum-size=200M".into(),
                    explanation: format!(
                        "Recupera espacio de logs del sistema en {mount}. Si no basta, \
                         inspecciona con: sudo du -xhd1 {mount} | sort -h | tail -20"
                    ),
                }),
            );
        }
    }

    // Capacity forecast from the 30-day history.
    if let Some(worst) = predictions
        .iter()
        .filter(|prediction| prediction.days_left < 7.0)
        .min_by(|a, b| a.days_left.total_cmp(&b.days_left))
    {
        let (level, _) = if worst.days_left < 2.0 {
            ("critical", "crítico")
        } else {
            ("warning", "aviso")
        };
        let quoted = shell_quote(&worst.mount);
        report.add(
            Finding {
                id: format!("disk.forecast:{}", worst.mount),
                severity: level.into(),
                title: format!(
                    "Al disco {} le quedan {:.1} días",
                    worst.mount, worst.days_left
                ),
                detail: "La tendencia de los últimos 7 días indica que se llenará pronto.".into(),
                evidence: json!({
                    "mount": worst.mount,
                    "days_left": worst.days_left,
                }),
                suggestion: Some(format!("disk.inspect:{}", worst.mount)),
            },
            Some(Suggestion {
                id: format!("disk.inspect:{}", worst.mount),
                finding: format!("disk.forecast:{}", worst.mount),
                risk: "low".into(),
                command: format!("sudo du -xhd1 {quoted} | sort -h | tail -20"),
                explanation: "Lista los directorios que más ocupan para decidir qué mover.".into(),
            }),
        );
    }

    // Memory pressure.
    if sample.mem.total > 0 {
        let used_pct = sample.mem.used as f64 / sample.mem.total as f64 * 100.0;
        if used_pct >= 90.0 {
            let (level, _) = severity(used_pct);
            report.add(
                Finding {
                    id: "memory.pressure".into(),
                    severity: level.into(),
                    title: format!("Memoria al {used_pct:.1} %"),
                    detail: format!(
                        "{} de {} en uso; el OOM killer puede matar servicios.",
                        bytes(sample.mem.used),
                        bytes(sample.mem.total)
                    ),
                    evidence: json!({
                        "used_pct": used_pct,
                        "used_bytes": sample.mem.used,
                        "total_bytes": sample.mem.total,
                    }),
                    suggestion: Some("memory.inspect".into()),
                },
                Some(Suggestion {
                    id: "memory.inspect".into(),
                    finding: "memory.pressure".into(),
                    risk: "low".into(),
                    command: "ps -eo pid,comm,%mem --sort=-%mem | head -15".into(),
                    explanation: "Muestra los procesos que más memoria consumen.".into(),
                }),
            );
        }
    }

    // Temperature.
    if let Some((label, celsius)) = sample
        .temps
        .iter()
        .map(|temp| (temp.label.clone(), temp.celsius as f64))
        .max_by(|a, b| a.1.total_cmp(&b.1))
    {
        if celsius >= 80.0 {
            let (level, _) = if celsius >= 90.0 {
                ("critical", "crítico")
            } else {
                ("warning", "aviso")
            };
            report.add(
                Finding {
                    id: "temperature.high".into(),
                    severity: level.into(),
                    title: format!("{label} a {celsius:.1} °C"),
                    detail: "Temperatura alta: revisa ventilación, polvo o carga sostenida.".into(),
                    evidence: json!({ "sensor": label, "celsius": celsius }),
                    suggestion: Some("temperature.inspect".into()),
                },
                Some(Suggestion {
                    id: "temperature.inspect".into(),
                    finding: "temperature.high".into(),
                    risk: "low".into(),
                    command: "sensors".into(),
                    explanation: "Muestra todos los sensores disponibles (paquete lm-sensors)."
                        .into(),
                }),
            );
        }
    }

    // Reachability: the app is talking to us, but endpoints may be loopback only
    // when the phone is using an SSH tunnel or the host has no LAN address.
    if endpoints.is_empty() {
        report.add(
            Finding {
                id: "network.loopback_only".into(),
                severity: "critical".into(),
                title: "El host sólo escucha en loopback".into(),
                detail: "El móvil no podrá alcanzarlo fuera de un túnel. Comprueba la interfaz de red o Tailscale.".into(),
                evidence: json!({ "endpoints": [] }),
                suggestion: Some("network.bring_up".into()),
            },
            Some(Suggestion {
                id: "network.bring_up".into(),
                finding: "network.loopback_only".into(),
                risk: "medium".into(),
                command: "sudo tailscale up".into(),
                explanation: "Si Tailscale está instalado, publica el host en el tailnet. También puedes conectar la interfaz LAN.".into(),
            }),
        );
    }

    // State directory must not be readable by other local users.
    if let Ok(metadata) = std::fs::metadata(state_dir) {
        use std::os::unix::fs::PermissionsExt;
        let mode = metadata.permissions().mode() & 0o777;
        if mode & 0o077 != 0 {
            let path = state_dir.display().to_string();
            report.add(
                Finding {
                    id: "security.state_dir".into(),
                    severity: "warning".into(),
                    title: "El directorio de estado es legible por otros usuarios".into(),
                    detail: format!(
                        "{} tiene permisos {:o}; contiene la base de datos, el certificado y la clave TLS.",
                        path, mode
                    ),
                    evidence: json!({ "path": path, "mode": format!("{mode:o}") }),
                    suggestion: Some("security.chmod_state".into()),
                },
                Some(Suggestion {
                    id: "security.chmod_state".into(),
                    finding: "security.state_dir".into(),
                    risk: "low".into(),
                    command: format!("chmod 700 {}", shell_quote(&path)),
                    explanation: "Deja el estado accesible sólo al usuario del agente.".into(),
                }),
            );
        }
    }

    // Informational: no alert rules configured.
    if rules == 0 {
        report.findings.push(Finding {
            id: "alerts.none".into(),
            severity: "info".into(),
            title: "Sin reglas de alerta".into(),
            detail: format!(
                "{} no tiene ninguna regla activa; nadie te avisará de un disco lleno.",
                host.hostname
            ),
            evidence: json!({ "rules": 0 }),
            suggestion: None,
        });
    }

    report
}

fn bytes(value: u64) -> String {
    const UNITS: [&str; 5] = ["B", "KiB", "MiB", "GiB", "TiB"];
    let mut size = value as f64;
    let mut unit = 0;
    while size >= 1024.0 && unit < UNITS.len() - 1 {
        size /= 1024.0;
        unit += 1;
    }
    if unit == 0 {
        format!("{value} B")
    } else {
        format!("{size:.1} {}", UNITS[unit])
    }
}

fn shell_quote(value: &str) -> String {
    if !value.is_empty()
        && value
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '/' | '.' | '_' | '-'))
    {
        value.to_string()
    } else {
        format!("'{}'", value.replace('\'', "'\\''"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::collect::{Cpu, Disk, Mem, Net, Temp};

    fn host() -> HostInfo {
        HostInfo {
            hostname: "test".into(),
            os: "Linux".into(),
            kernel: "test".into(),
            arch: "x86_64".into(),
            boot_time: 0,
            cpu_model: "test".into(),
            cores: 2,
            mem_total: 16 * 1024 * 1024 * 1024,
            form_factor: "laptop".into(),
            virtualized: false,
        }
    }

    fn sample(disk_pct: f64, mem_pct: f64, celsius: f32) -> Sample {
        let total = 100 * 1024 * 1024 * 1024u64;
        Sample {
            t: 1_700_000_000,
            cpu: Cpu {
                total: 10.0,
                cores: vec![10.0],
                load1: 0.1,
                load5: 0.1,
                load15: 0.1,
            },
            mem: Mem {
                total: 16 * 1024 * 1024 * 1024,
                used: (16.0 * 1024.0 * 1024.0 * 1024.0 * mem_pct / 100.0) as u64,
                available: 0,
                swap_total: 0,
                swap_used: 0,
            },
            net: Net {
                rx_bps: 0,
                tx_bps: 0,
                ifaces: vec![],
            },
            disks: vec![Disk {
                mount: "/".into(),
                fs: "ext4".into(),
                total,
                used: (total as f64 * disk_pct / 100.0) as u64,
                r_bps: 0,
                w_bps: 0,
            }],
            temps: vec![Temp {
                label: "Tctl".into(),
                celsius,
            }],
            power: None,
            uptime: 100,
        }
    }

    fn temp_dir() -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "vitals-diag-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn healthy_host_has_no_warnings() {
        let dir = temp_dir();
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o700)).unwrap();
        let report = report(
            &host(),
            Some(&sample(40.0, 30.0, 50.0)),
            &["https://192.168.1.10:8443".into()],
            2,
            &[],
            &dir,
        );
        assert!(report.findings.iter().all(|f| f.severity == "info"));
        assert!(report.suggestions.is_empty());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn disk_memory_and_temperature_emit_exact_commands() {
        let dir = temp_dir();
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o755)).unwrap();
        let report = report(
            &host(),
            Some(&sample(96.0, 95.0, 92.0)),
            &[],
            0,
            &[Prediction {
                mount: "/".into(),
                days_left: 1.5,
            }],
            &dir,
        );
        let ids: Vec<&str> = report.findings.iter().map(|f| f.id.as_str()).collect();
        assert!(ids.contains(&"disk.pressure:/"));
        assert!(ids.contains(&"disk.forecast:/"));
        assert!(ids.contains(&"memory.pressure"));
        assert!(ids.contains(&"temperature.high"));
        assert!(ids.contains(&"network.loopback_only"));
        assert!(ids.contains(&"security.state_dir"));
        assert!(ids.contains(&"alerts.none"));

        for suggestion in &report.suggestions {
            assert!(!suggestion.command.is_empty());
            assert!(report
                .findings
                .iter()
                .any(|finding| finding.id == suggestion.finding));
        }
        let disk = report
            .suggestions
            .iter()
            .find(|s| s.id == "disk.free_space:/")
            .unwrap();
        assert_eq!(disk.command, "sudo journalctl --vacuum-size=200M");
        let chmod = report
            .suggestions
            .iter()
            .find(|s| s.id == "security.chmod_state")
            .unwrap();
        assert!(chmod.command.starts_with("chmod 700 "));
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn a_warming_collector_only_reports_info() {
        let dir = temp_dir();
        let report = report(&host(), None, &[], 0, &[], &dir);
        assert_eq!(report.findings.len(), 1);
        assert_eq!(report.findings[0].severity, "info");
        assert!(report.suggestions.is_empty());
        std::fs::remove_dir_all(&dir).ok();
    }
}

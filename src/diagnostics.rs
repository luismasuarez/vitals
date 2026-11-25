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
use crate::collect::access::AccessSnapshot;
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
    access: Option<&AccessSnapshot>,
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

    if let Some(access) = access {
        let config_read = !access.ssh.files.is_empty();

        if config_read && access.ssh.password_auth {
            report.add(
                Finding {
                    id: "ssh.password_auth".into(),
                    severity: "warning".into(),
                    title: "SSH acepta contraseñas".into(),
                    detail: "PasswordAuthentication está activo: cualquiera que llegue al puerto 22 puede intentar adivinar una contraseña. Las claves públicas y fail2ban reducen el riesgo, pero desactivar la contraseña lo elimina.".into(),
                    evidence: json!({
                        "password_auth": true,
                        "files": &access.ssh.files,
                    }),
                    suggestion: Some("ssh.disable_password_auth".into()),
                },
                Some(Suggestion {
                    id: "ssh.disable_password_auth".into(),
                    finding: "ssh.password_auth".into(),
                    risk: "medium".into(),
                    command: "printf 'PasswordAuthentication no\\n' | sudo tee /etc/ssh/sshd_config.d/99-vitals.conf >/dev/null && sudo sshd -t && sudo systemctl reload ssh".into(),
                    explanation: "Deja sólo acceso con clave. Antes de recargar, comprueba que tu clave pública entra; si no, perderás el acceso.".into(),
                }),
            );
        }

        if config_read && access.ssh.root_login.eq_ignore_ascii_case("yes") {
            report.add(
                Finding {
                    id: "ssh.root_login".into(),
                    severity: "warning".into(),
                    title: "SSH permite entrar como root con contraseña".into(),
                    detail: "PermitRootLogin está en yes. Es la cuenta que más se intenta y la que más duele si cae.".into(),
                    evidence: json!({ "root_login": &access.ssh.root_login }),
                    suggestion: Some("ssh.restrict_root_login".into()),
                },
                Some(Suggestion {
                    id: "ssh.restrict_root_login".into(),
                    finding: "ssh.root_login".into(),
                    risk: "medium".into(),
                    command: "printf 'PermitRootLogin prohibit-password\\n' | sudo tee /etc/ssh/sshd_config.d/98-vitals-root.conf >/dev/null && sudo sshd -t && sudo systemctl reload ssh".into(),
                    explanation: "Root sigue pudiendo entrar con clave, pero no con contraseña.".into(),
                }),
            );
        }

        if config_read && access.source == "none" {
            report.add(
                Finding {
                    id: "ssh.logs_unreadable".into(),
                    severity: "warning".into(),
                    title: "SSH está instalado pero sus registros no son legibles".into(),
                    detail: "Hay configuración de OpenSSH, pero el agente no pudo leer ni auth.log ni el journal. Sin eso no puede mostrar accesos ni fallos.".into(),
                    evidence: json!({ "source": &access.source }),
                    suggestion: Some("ssh.grant_log_access".into()),
                },
                Some(Suggestion {
                    id: "ssh.grant_log_access".into(),
                    finding: "ssh.logs_unreadable".into(),
                    risk: "low".into(),
                    command: "printf '[Service]\\nSupplementaryGroups=adm systemd-journal\\n' | sudo tee /etc/systemd/system/vitals.service.d/10-logs.conf >/dev/null && sudo systemctl daemon-reload && sudo systemctl restart vitals".into(),
                    explanation: "Da al servicio acceso de sólo lectura a auth.log y al journal. Si usas un servicio de usuario, añade tu usuario a los grupos adm y systemd-journal.".into(),
                }),
            );
        }

        if access.failures_24h >= 5 {
            let top = access
                .sources
                .iter()
                .filter(|source| source.failed > 0)
                .max_by_key(|source| source.failed);
            let (title, severity) = if access.failures_24h >= 50 {
                ("Ataque de fuerza bruta contra SSH", "critical")
            } else {
                ("Intentos fallidos de entrar por SSH", "warning")
            };
            let detail = match top {
                Some(source) => format!(
                    "{} intentos fallidos en 24 h desde {} direcciones. La más activa es {} con {}.",
                    access.failures_24h,
                    access.sources.iter().filter(|s| s.failed > 0).count(),
                    source.host,
                    source.failed
                ),
                None => format!("{} intentos fallidos en 24 h.", access.failures_24h),
            };
            let suggestion = top
                .filter(|source| source.host.parse::<std::net::IpAddr>().is_ok())
                .map(|source| Suggestion {
                    id: "ssh.block_source".into(),
                    finding: "ssh.failed_logins".into(),
                    risk: "medium".into(),
                    command: format!("sudo ufw deny from {}", source.host),
                    explanation: "Bloquea la IP más agresiva. Si usas nftables o firewalld, aplica la regla equivalente.".into(),
                });
            report.add(
                Finding {
                    id: "ssh.failed_logins".into(),
                    severity: severity.into(),
                    title: title.into(),
                    detail,
                    evidence: json!({
                        "failures_24h": access.failures_24h,
                        "sources": access.sources.iter().filter(|s| s.failed > 0).count(),
                        "top": top.map(|s| json!({ "host": s.host, "failed": s.failed })),
                    }),
                    suggestion: suggestion.as_ref().map(|s| s.id.clone()),
                },
                suggestion,
            );
        }

        if let Some(source) = access
            .sources
            .iter()
            .find(|source| source.new && source.ok > 0)
        {
            report.add(
                Finding {
                    id: "ssh.new_source".into(),
                    severity: "warning".into(),
                    title: "Acceso SSH desde una dirección nueva".into(),
                    detail: format!(
                        "Entró como {} desde {}, una dirección que no aparecía en los últimos 24 h. Si no fuiste tú, revoca las claves y revisa los procesos.",
                        source.users.join(", "),
                        source.host
                    ),
                    evidence: json!({
                        "host": source.host,
                        "users": &source.users,
                        "first_t": source.first_t,
                        "last_t": source.last_t,
                    }),
                    suggestion: None,
                },
                None,
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
            access: None,
            uptime: 100,
        }
    }

    fn temp_dir() -> std::path::PathBuf {
        use std::sync::atomic::{AtomicU64, Ordering};
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let dir = std::env::temp_dir().join(format!(
            "vitals-diag-{}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos(),
            NEXT.fetch_add(1, Ordering::Relaxed)
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
            None,
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
            None,
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
        let report = report(&host(), None, None, &[], 0, &[], &dir);
        assert_eq!(report.findings.len(), 1);
        assert_eq!(report.findings[0].severity, "info");
        assert!(report.suggestions.is_empty());
        std::fs::remove_dir_all(&dir).ok();
    }

    fn access_report() -> AccessSnapshot {
        use crate::collect::access::{Source, SshConfig};
        AccessSnapshot {
            updated: 1_700_000_000,
            source: "journal".into(),
            covered_s: 2 * 86_400,
            sessions: Vec::new(),
            logins: Vec::new(),
            failures: Vec::new(),
            sources: vec![Source {
                host: "203.0.113.9".into(),
                public: true,
                ok: 1,
                failed: 37,
                first_t: 1_699_999_000,
                last_t: 1_700_000_000,
                users: vec!["root".into()],
                new: true,
            }],
            ssh: SshConfig {
                password_auth: true,
                root_login: "yes".into(),
                files: vec!["/etc/ssh/sshd_config".into()],
            },
            logins_24h: 1,
            failures_24h: 37,
            logins_7d: 1,
            failures_7d: 37,
            last_login: None,
            last_failure: None,
        }
    }

    #[test]
    fn ssh_risks_are_specific_and_actionable() {
        let dir = temp_dir();
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(&dir, std::fs::Permissions::from_mode(0o700)).unwrap();
        let report = report(
            &host(),
            Some(&sample(40.0, 30.0, 50.0)),
            Some(&access_report()),
            &["https://192.168.1.10:8443".into()],
            2,
            &[],
            &dir,
        );
        let ids: Vec<&str> = report.findings.iter().map(|f| f.id.as_str()).collect();
        assert!(ids.contains(&"ssh.password_auth"));
        assert!(ids.contains(&"ssh.root_login"));
        assert!(ids.contains(&"ssh.failed_logins"));
        assert!(ids.contains(&"ssh.new_source"));
        let new_source = report
            .findings
            .iter()
            .find(|finding| finding.id == "ssh.new_source")
            .unwrap();
        assert_eq!(new_source.title, "Acceso SSH desde una dirección nueva");
        assert!(new_source.detail.contains("últimos 24 h"));

        let block = report
            .suggestions
            .iter()
            .find(|s| s.id == "ssh.block_source")
            .unwrap();
        assert_eq!(block.command, "sudo ufw deny from 203.0.113.9");
        for suggestion in &report.suggestions {
            assert!(report
                .findings
                .iter()
                .any(|finding| finding.id == suggestion.finding));
        }
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn unreadable_logs_are_reported_with_the_group_fix() {
        let dir = temp_dir();
        let mut access = access_report();
        access.source = "none".into();
        access.failures_24h = 0;
        access.sources.clear();
        let report = report(
            &host(),
            Some(&sample(40.0, 30.0, 50.0)),
            Some(&access),
            &[],
            0,
            &[],
            &dir,
        );
        let finding = report
            .findings
            .iter()
            .find(|finding| finding.id == "ssh.logs_unreadable")
            .expect("logs finding");
        assert_eq!(finding.severity, "warning");
        let fix = report
            .suggestions
            .iter()
            .find(|suggestion| suggestion.id == "ssh.grant_log_access")
            .expect("log group suggestion");
        assert!(fix
            .command
            .contains("SupplementaryGroups=adm systemd-journal"));
        std::fs::remove_dir_all(&dir).ok();
    }
}

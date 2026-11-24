use std::net::IpAddr;

use anyhow::Result;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Kind {
    /// Reachable from the LAN (what the phone uses at home).
    Lan,
    /// Tailscale CGNAT or ULA range.
    Tailscale,
    Loopback,
    Public,
}

#[derive(Debug, Clone)]
pub struct Candidate {
    pub iface: String,
    pub addr: IpAddr,
    pub kind: Kind,
}

/// Interface name prefixes that never carry useful client traffic.
const VIRTUAL_PREFIXES: &[&str] = &[
    "docker", "br-", "veth", "virbr", "lxcbr", "lxc", "cni", "flannel", "podman", "proton",
    "ipv6leak", "zt", "vmnet", "vboxnet",
];

pub fn is_loopback(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => v4.is_loopback(),
        IpAddr::V6(v6) => v6.is_loopback(),
    }
}

pub fn is_tailscale(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            let [a, b, ..] = v4.octets();
            a == 100 && (64..=127).contains(&b)
        }
        // Tailscale ULA prefix fd7a:115c:a1e0::/48
        IpAddr::V6(v6) => {
            let seg = v6.segments();
            seg[0] == 0xfd7a && seg[1] == 0x115c && seg[2] == 0xa1e0
        }
    }
}

pub fn is_private(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => v4.is_private(),
        IpAddr::V6(v6) => (v6.segments()[0] & 0xfe00) == 0xfc00,
    }
}

pub fn classify(ip: IpAddr) -> Kind {
    if is_loopback(ip) {
        Kind::Loopback
    } else if is_tailscale(ip) {
        Kind::Tailscale
    } else if is_private(ip) {
        Kind::Lan
    } else {
        Kind::Public
    }
}

fn is_virtual_iface(name: &str) -> bool {
    VIRTUAL_PREFIXES.iter().any(|p| name.starts_with(p))
}

fn is_link_local(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V6(v6) => (v6.segments()[0] & 0xffc0) == 0xfe80,
        IpAddr::V4(_) => false,
    }
}

/// Addresses the agent may bind to, ordered by client preference.
pub fn candidates(
    mode: crate::config::BindMode,
    allow_public: bool,
    extra: &[IpAddr],
) -> Result<Vec<Candidate>> {
    use crate::config::BindMode;

    let mut out: Vec<Candidate> = Vec::new();
    for iface in if_addrs::get_if_addrs()? {
        let ip = iface.addr.ip();
        if is_link_local(ip) {
            continue;
        }
        let kind = classify(ip);
        if is_virtual_iface(&iface.name) && kind != Kind::Tailscale {
            continue;
        }
        match mode {
            BindMode::Auto => {
                if kind == Kind::Public && !allow_public {
                    continue;
                }
            }
            BindMode::All => {}
        }
        if out.iter().any(|c| c.addr == ip) {
            continue;
        }
        out.push(Candidate {
            iface: iface.name,
            addr: ip,
            kind,
        });
    }

    for ip in extra {
        if out.iter().any(|c| c.addr == *ip) {
            continue;
        }
        out.push(Candidate {
            iface: "config".into(),
            addr: *ip,
            kind: classify(*ip),
        });
    }

    out.sort_by_key(|c| (c.kind, c.addr));
    Ok(out)
}

/// Formats an address for a URL, bracketing IPv6 literals.
pub fn url_host(ip: IpAddr) -> String {
    match ip {
        IpAddr::V4(v4) => v4.to_string(),
        IpAddr::V6(v6) => format!("[{v6}]"),
    }
}

pub fn tailscale_dns_suffix() -> Option<String> {
    // `tailscale status --json` exposes the tailnet DNS name; reading it keeps
    // MagicDNS working even when the CGNAT IP changes. Best-effort only.
    let out = std::process::Command::new("tailscale")
        .args(["status", "--json"])
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    let v: serde_json::Value = serde_json::from_slice(&out.stdout).ok()?;
    v.get("Self")
        .and_then(|s| s.get("DNSName"))
        .and_then(|d| d.as_str())
        .map(|s| s.trim_end_matches('.').to_string())
}

/// Addresses that must be stopped and started when the local set changes.
pub fn plan_changes(active: &[IpAddr], wanted: &[IpAddr]) -> (Vec<IpAddr>, Vec<IpAddr>) {
    use std::collections::HashSet;
    let active: HashSet<IpAddr> = active.iter().copied().collect();
    let wanted: HashSet<IpAddr> = wanted.iter().copied().collect();
    let mut stop: Vec<IpAddr> = active.difference(&wanted).copied().collect();
    let mut start: Vec<IpAddr> = wanted.difference(&active).copied().collect();
    stop.sort();
    start.sort();
    (stop, start)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{Ipv4Addr, Ipv6Addr};

    fn v4(s: &str) -> IpAddr {
        IpAddr::V4(s.parse::<Ipv4Addr>().unwrap())
    }
    fn v6(s: &str) -> IpAddr {
        IpAddr::V6(s.parse::<Ipv6Addr>().unwrap())
    }

    #[test]
    fn classifies_tailscale_and_lan() {
        assert_eq!(classify(v4("100.71.72.48")), Kind::Tailscale);
        assert_eq!(classify(v4("192.168.1.104")), Kind::Lan);
        assert_eq!(classify(v4("127.0.0.1")), Kind::Loopback);
        assert_eq!(classify(v4("8.8.8.8")), Kind::Public);
        assert_eq!(classify(v6("fd7a:115c:a1e0::1")), Kind::Tailscale);
        assert_eq!(classify(v6("::1")), Kind::Loopback);
    }

    #[test]
    fn rejects_container_bridges() {
        assert!(is_virtual_iface("docker0"));
        assert!(is_virtual_iface("br-13a1dd3a64b7"));
        assert!(is_virtual_iface("veth24f5227"));
        assert!(!is_virtual_iface("enp2s0"));
        assert!(!is_virtual_iface("tailscale0"));
    }

    #[test]
    fn plans_listener_changes() {
        let a = v4("192.168.1.10");
        let b = v4("100.64.0.2");
        let c = v4("10.0.0.5");
        let (stop, start) = plan_changes(&[a, b], &[b, c]);
        assert_eq!(stop, vec![a]);
        assert_eq!(start, vec![c]);
    }

    #[test]
    fn brackets_ipv6_urls() {
        assert_eq!(url_host(v4("192.168.1.5")), "192.168.1.5");
        assert_eq!(url_host(v6("fd7a:115c:a1e0::1")), "[fd7a:115c:a1e0::1]");
    }
}

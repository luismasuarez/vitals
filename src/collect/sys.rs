use std::time::{Duration, Instant};

use serde::Serialize;
use sysinfo::{Components, Disks, Networks, ProcessesToUpdate, System};

use super::{Cpu, Disk, Iface, Mem, Net, Sample, Temp};

/// Filesystems that are noise on a host overview.
const IGNORED_FS: &[&str] = &[
    "tmpfs", "devtmpfs", "squashfs", "overlay", "ramfs", "proc", "sysfs", "devfs", "nsfs",
];

// Virtual interfaces that only add noise: container bridges, VPN tunnels and
// leak-guard devices. Tailscale is intentionally kept.
const IGNORED_IFACE_PREFIXES: &[&str] = &[
    "docker", "br-", "veth", "virbr", "lxcbr", "lxc", "cni", "flannel", "podman", "proton",
    "ipv6leak", "zt", "vmnet", "vboxnet",
];

fn ignored_iface(name: &str) -> bool {
    name == "lo" || IGNORED_IFACE_PREFIXES.iter().any(|p| name.starts_with(p))
}

pub struct Sampler {
    sys: System,
    disks: Disks,
    nets: Networks,
    comps: Components,
    last: Instant,
}

impl Sampler {
    pub fn new() -> Self {
        let sys = System::new_all();
        let disks = Disks::new_with_refreshed_list();
        let nets = Networks::new_with_refreshed_list();
        let comps = Components::new_with_refreshed_list();
        Self {
            sys,
            disks,
            nets,
            comps,
            last: Instant::now(),
        }
    }

    pub fn tick(&mut self) -> Sample {
        let elapsed = self.last.elapsed().as_secs_f64().max(0.001);
        self.last = Instant::now();

        self.sys.refresh_cpu_usage();
        self.sys.refresh_memory();
        self.disks.refresh(true);
        self.nets.refresh(true);
        self.comps.refresh(true);

        let load = System::load_average();
        let cpu = Cpu {
            total: self.sys.global_cpu_usage(),
            cores: self.sys.cpus().iter().map(|c| c.cpu_usage()).collect(),
            load1: load.one,
            load5: load.five,
            load15: load.fifteen,
        };

        let mem = Mem {
            total: self.sys.total_memory(),
            used: self.sys.used_memory(),
            available: self.sys.available_memory(),
            swap_total: self.sys.total_swap(),
            swap_used: self.sys.used_swap(),
        };

        let mut ifaces = Vec::new();
        for (name, data) in self.nets.iter() {
            if ignored_iface(name) {
                continue;
            }
            ifaces.push(Iface {
                name: name.clone(),
                rx_bps: (data.received() as f64 / elapsed) as u64,
                tx_bps: (data.transmitted() as f64 / elapsed) as u64,
            });
        }
        ifaces.sort_by_key(|i| std::cmp::Reverse(i.rx_bps + i.tx_bps));
        let net = Net {
            rx_bps: ifaces.iter().map(|i| i.rx_bps).sum(),
            tx_bps: ifaces.iter().map(|i| i.tx_bps).sum(),
            ifaces,
        };

        let mut disks = Vec::new();
        for disk in &self.disks {
            let fs = disk.file_system().to_string_lossy().to_string();
            if IGNORED_FS.iter().any(|f| fs == *f) {
                continue;
            }
            let mount = disk.mount_point().to_string_lossy().to_string();
            if disks.iter().any(|d: &Disk| d.mount == mount) {
                continue;
            }
            let total = disk.total_space();
            let usage = disk.usage();
            disks.push(Disk {
                mount,
                fs,
                total,
                used: total.saturating_sub(disk.available_space()),
                r_bps: (usage.read_bytes as f64 / elapsed) as u64,
                w_bps: (usage.written_bytes as f64 / elapsed) as u64,
            });
        }

        // Sensors reporting 0.0 (or NaN) are placeholders, not readings.
        let mut temps: Vec<Temp> = Vec::new();
        for comp in self.comps.iter() {
            if let Some(c) = comp.temperature() {
                let label = comp.label().to_string();
                if c.is_nan() || c <= 0.0 || temps.iter().any(|t| t.label == label) {
                    continue;
                }
                temps.push(Temp { label, celsius: c });
            }
        }
        temps.sort_by(|a, b| a.label.cmp(&b.label));

        Sample {
            t: crate::store::now_ts() as u64,
            cpu,
            mem,
            net,
            disks,
            temps,
            power: super::power::read(),
            uptime: System::uptime(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Sort {
    Cpu,
    Mem,
}

#[derive(Debug, Clone, Serialize)]
pub struct ProcRow {
    pub pid: u32,
    pub name: String,
    pub cpu: f32,
    pub mem: u64,
}

/// Process list is expensive to build, so it is cached for a couple of
/// seconds and refreshed lazily when a client asks for it.
pub struct ProcSampler {
    sys: System,
    at: Option<Instant>,
}

impl ProcSampler {
    pub fn new() -> Self {
        Self {
            sys: System::new(),
            at: None,
        }
    }

    pub fn snapshot(&mut self, sort: Sort, limit: usize) -> Vec<ProcRow> {
        if self.at.is_none() {
            // CPU per process is a delta between two refreshes; without this
            // warm-up every row reports 0.0% on the first request.
            self.sys.refresh_processes(ProcessesToUpdate::All, true);
            std::thread::sleep(Duration::from_millis(180));
        }
        let stale = self
            .at
            .map(|t| t.elapsed() > Duration::from_secs(2))
            .unwrap_or(true);
        if stale {
            self.sys.refresh_processes(ProcessesToUpdate::All, true);
            self.at = Some(Instant::now());
        }
        let mut rows: Vec<ProcRow> = self
            .sys
            .processes()
            .iter()
            .map(|(pid, p)| ProcRow {
                pid: pid.as_u32(),
                name: p.name().to_string_lossy().to_string(),
                cpu: p.cpu_usage(),
                mem: p.memory(),
            })
            .filter(|p| !p.name.is_empty())
            .collect();

        match sort {
            Sort::Cpu => rows.sort_by(|a, b| {
                b.cpu
                    .partial_cmp(&a.cpu)
                    .unwrap_or(std::cmp::Ordering::Equal)
            }),
            Sort::Mem => rows.sort_by_key(|p| std::cmp::Reverse(p.mem)),
        }
        rows.truncate(limit.clamp(1, 50));
        rows
    }
}

pub mod sys;

pub use sys::{ProcRow, ProcSampler, Sampler, Sort};

use serde::Serialize;

use crate::store::Point;

#[derive(Debug, Clone, Serialize)]
pub struct Sample {
    pub t: u64,
    pub cpu: Cpu,
    pub mem: Mem,
    pub net: Net,
    pub disks: Vec<Disk>,
    pub temps: Vec<Temp>,
    pub uptime: u64,
}

#[derive(Debug, Clone, Serialize)]
pub struct Cpu {
    /// Percent, 0..100.
    pub total: f32,
    pub cores: Vec<f32>,
    pub load1: f64,
    pub load5: f64,
    pub load15: f64,
}

#[derive(Debug, Clone, Serialize)]
pub struct Mem {
    pub total: u64,
    pub used: u64,
    pub available: u64,
    pub swap_total: u64,
    pub swap_used: u64,
}

#[derive(Debug, Clone, Serialize)]
pub struct Net {
    pub rx_bps: u64,
    pub tx_bps: u64,
    pub ifaces: Vec<Iface>,
}

#[derive(Debug, Clone, Serialize)]
pub struct Iface {
    pub name: String,
    pub rx_bps: u64,
    pub tx_bps: u64,
}

#[derive(Debug, Clone, Serialize)]
pub struct Disk {
    pub mount: String,
    pub fs: String,
    pub total: u64,
    pub used: u64,
    pub r_bps: u64,
    pub w_bps: u64,
}

#[derive(Debug, Clone, Serialize)]
pub struct Temp {
    pub label: String,
    pub celsius: f32,
}

impl Sample {
    pub fn to_point(&self) -> Point {
        Point {
            t: self.t as i64,
            cpu: self.cpu.total as f64,
            mem_used: self.mem.used as i64,
            net_rx: self.net.rx_bps as i64,
            net_tx: self.net.tx_bps as i64,
            disk_r: self.disks.iter().map(|d| d.r_bps).sum::<u64>() as i64,
            disk_w: self.disks.iter().map(|d| d.w_bps).sum::<u64>() as i64,
        }
    }
}

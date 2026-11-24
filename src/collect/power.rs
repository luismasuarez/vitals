//! Battery and mains state, read from `/sys/class/power_supply`.
//!
//! `sysinfo` does not expose batteries, so this module reads the kernel
//! interface directly. Drivers disagree on units and on which attributes they
//! provide: some report `energy_*` (µWh), others `charge_*` (µAh) plus a
//! voltage. Everything is normalised to Wh, W and V before it reaches the API.

use std::fs;
use std::path::{Path, PathBuf};

use serde::Serialize;

const ROOT: &str = "/sys/class/power_supply";

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Power {
    /// Any mains/USB supply is online.
    pub ac_online: bool,
    /// The host is running from battery right now.
    pub on_battery: bool,
    /// Capacity of the whole battery pack, 0..100.
    pub percent: Option<f32>,
    /// `charging`, `discharging`, `full`, `not_charging` or `unknown`.
    pub status: String,
    /// Instant battery power: discharge rate or charge rate, in watts.
    pub power_w: Option<f32>,
    pub seconds_left: Option<u64>,
    pub seconds_to_full: Option<u64>,
    /// Full capacity as a percentage of the original design capacity.
    pub health_pct: Option<f32>,
    pub cycles: Option<u32>,
    pub batteries: Vec<Battery>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Battery {
    pub name: String,
    pub present: bool,
    pub status: String,
    pub percent: Option<f32>,
    pub power_w: Option<f32>,
    pub energy_now_wh: Option<f32>,
    pub energy_full_wh: Option<f32>,
    pub energy_full_design_wh: Option<f32>,
    pub voltage_v: Option<f32>,
    pub seconds_left: Option<u64>,
    pub seconds_to_full: Option<u64>,
    pub health_pct: Option<f32>,
    pub cycles: Option<u32>,
    pub technology: Option<String>,
    pub model: Option<String>,
}

/// Reads the live power state. `None` means the host has no readable battery,
/// so desktops and VPS keep the exact same payload as before.
pub fn read() -> Option<Power> {
    read_from(Path::new(ROOT))
}

/// Used to tell a laptop from a desktop when SMBIOS does not say.
pub fn has_battery() -> bool {
    has_battery_in(Path::new(ROOT))
}

fn read_from(root: &Path) -> Option<Power> {
    let supplies = supply_dirs(root);
    let mut batteries = Vec::new();
    let mut ac_online = false;

    for dir in supplies {
        let Some(kind) = read_str(&dir, "type").map(|value| value.to_ascii_lowercase()) else {
            continue;
        };
        match kind.as_str() {
            "battery" | "ups" => {
                if read_u64(&dir, "present") == Some(0) {
                    continue;
                }
                if let Some(battery) = read_battery(&dir) {
                    batteries.push(battery);
                }
            }
            "mains" | "usb" | "usb-c" | "wireless" => {
                if let Some(online) = read_u64(&dir, "online") {
                    ac_online |= online > 0;
                }
            }
            _ => {}
        }
    }

    if batteries.is_empty() {
        return None;
    }

    // Some drivers expose the battery but not the AC supply, and others report
    // the AC as offline while the pack is clearly charging. Either way, the
    // charge state proves that the adapter is connected.
    if batteries.iter().any(|battery| {
        matches!(
            battery.status.as_str(),
            "charging" | "full" | "not_charging"
        )
    }) {
        ac_online = true;
    }

    let status = aggregate_status(&batteries);
    let power_w = sum_known(batteries.iter().filter_map(|battery| battery.power_w));
    let (seconds_left, seconds_to_full) = aggregate_times(&batteries, &status);

    Some(Power {
        ac_online,
        on_battery: batteries
            .iter()
            .any(|battery| battery.status == "discharging"),
        percent: weighted_percent(&batteries).map(round1),
        status,
        power_w: power_w.map(round2),
        seconds_left,
        seconds_to_full,
        health_pct: weighted_health(&batteries).map(round1),
        cycles: batteries.iter().filter_map(|battery| battery.cycles).max(),
        batteries,
    })
}

fn read_battery(dir: &Path) -> Option<Battery> {
    let name = dir.file_name()?.to_string_lossy().to_string();
    let status = read_str(dir, "status")
        .map(|value| normalize_status(&value))
        .unwrap_or_else(|| "unknown".to_string());

    let voltage_v = read_f64(dir, "voltage_now")
        .or_else(|| read_f64(dir, "voltage_min_design"))
        .map(|microvolts| microvolts / 1_000_000.0);

    // Prefer energy_*; fall back to charge_* scaled by the pack voltage.
    let energy_now_wh = read_f64(dir, "energy_now").map(micro_wh_to_wh).or_else(|| {
        read_f64(dir, "charge_now").and_then(|micro_ah| charge_to_wh(micro_ah, voltage_v))
    });
    let energy_full_wh = read_f64(dir, "energy_full")
        .map(micro_wh_to_wh)
        .or_else(|| {
            read_f64(dir, "charge_full").and_then(|micro_ah| charge_to_wh(micro_ah, voltage_v))
        });
    let energy_full_design_wh = read_f64(dir, "energy_full_design")
        .map(micro_wh_to_wh)
        .or_else(|| {
            read_f64(dir, "charge_full_design")
                .and_then(|micro_ah| charge_to_wh(micro_ah, voltage_v))
        });

    let percent = read_f64(dir, "capacity")
        .map(|value| value as f32)
        .or_else(|| match (energy_now_wh, energy_full_wh) {
            (Some(now), Some(full)) if full > 0.0 => Some(now / full * 100.0),
            _ => None,
        })
        .map(|value| value.clamp(0.0, 100.0));

    let power_w = read_f64(dir, "power_now")
        .map(|microwatts| (microwatts / 1_000_000.0).abs() as f32)
        .filter(|watts| *watts > 0.0)
        .or_else(|| {
            // current_now is µA and voltage_now is µV: A * V = W.
            let current_a = read_f64(dir, "current_now")?.abs() / 1_000_000.0;
            let voltage_v = voltage_v?;
            let watts = (current_a * voltage_v) as f32;
            (watts > 0.0).then_some(watts)
        });

    let (mut seconds_left, mut seconds_to_full) =
        battery_times(energy_now_wh, energy_full_wh, power_w, status.as_str());

    // Last resort: some drivers expose charge and current but no voltage, so
    // neither energy nor power can be normalised. Charge/current still yields
    // hours directly.
    if seconds_left.is_none() && seconds_to_full.is_none() {
        if let Some(current) = read_f64(dir, "current_now")
            .map(f64::abs)
            .filter(|value| *value > 0.0)
        {
            let charge_now = read_f64(dir, "charge_now");
            let charge_full = read_f64(dir, "charge_full");
            match status.as_str() {
                "discharging" => {
                    seconds_left =
                        charge_now.map(|now| (now / current * 3_600.0).round().max(0.0) as u64);
                }
                "charging" => {
                    seconds_to_full = charge_full.zip(charge_now).map(|(full, now)| {
                        ((full - now).max(0.0) / current * 3_600.0).round().max(0.0) as u64
                    });
                }
                _ => {}
            }
        }
    }

    let health_pct = match (energy_full_wh, energy_full_design_wh) {
        (Some(full), Some(design)) if design > 0.0 => {
            Some((full / design * 100.0).clamp(0.0, 200.0))
        }
        _ => None,
    };

    Some(Battery {
        name,
        present: true,
        status,
        percent: percent.map(round1),
        power_w: power_w.map(round2),
        energy_now_wh: energy_now_wh.map(round2),
        energy_full_wh: energy_full_wh.map(round2),
        energy_full_design_wh: energy_full_design_wh.map(round2),
        voltage_v: voltage_v.map(|value| round2(value as f32)),
        seconds_left,
        seconds_to_full,
        health_pct: health_pct.map(round1),
        cycles: read_u64(dir, "cycle_count").and_then(|value| u32::try_from(value).ok()),
        technology: read_str(dir, "technology"),
        model: read_str(dir, "model_name"),
    })
}

fn battery_times(
    now_wh: Option<f32>,
    full_wh: Option<f32>,
    power_w: Option<f32>,
    status: &str,
) -> (Option<u64>, Option<u64>) {
    let Some(power) = power_w.filter(|watts| *watts > 0.0) else {
        return (None, None);
    };
    match status {
        "discharging" => (now_wh.map(|now| seconds(now.max(0.0), power)), None),
        "charging" => (
            None,
            full_wh
                .zip(now_wh)
                .map(|(full, now)| seconds((full - now).max(0.0), power)),
        ),
        _ => (None, None),
    }
}

fn aggregate_times(batteries: &[Battery], status: &str) -> (Option<u64>, Option<u64>) {
    let now_wh = sum_all(batteries.iter().map(|battery| battery.energy_now_wh));
    let full_wh = sum_all(batteries.iter().map(|battery| battery.energy_full_wh));
    let power_w = sum_all(batteries.iter().map(|battery| battery.power_w));

    match status {
        "discharging" => (
            now_wh
                .zip(power_w)
                .filter(|(_, power)| *power > 0.0)
                .map(|(now, power)| seconds(now, power))
                .or_else(|| {
                    batteries
                        .iter()
                        .filter_map(|battery| battery.seconds_left)
                        .min()
                }),
            None,
        ),
        "charging" => (
            None,
            full_wh
                .zip(now_wh)
                .zip(power_w)
                .filter(|((_, _), power)| *power > 0.0)
                .map(|((full, now), power)| seconds((full - now).max(0.0), power))
                .or_else(|| {
                    batteries
                        .iter()
                        .filter_map(|battery| battery.seconds_to_full)
                        .min()
                }),
        ),
        _ => (None, None),
    }
}

fn seconds(energy_wh: f32, power_w: f32) -> u64 {
    (energy_wh / power_w * 3_600.0).round().max(0.0) as u64
}

fn round1(value: f32) -> f32 {
    (value * 10.0).round() / 10.0
}

fn round2(value: f32) -> f32 {
    (value * 100.0).round() / 100.0
}

fn weighted_percent(batteries: &[Battery]) -> Option<f32> {
    let mut weighted = 0.0f32;
    let mut weight = 0.0f32;
    for battery in batteries {
        if let (Some(percent), Some(full)) = (battery.percent, battery.energy_full_wh) {
            weighted += percent * full;
            weight += full;
        }
    }
    if weight > 0.0 {
        return Some((weighted / weight).clamp(0.0, 100.0));
    }
    let values: Vec<f32> = batteries
        .iter()
        .filter_map(|battery| battery.percent)
        .collect();
    (!values.is_empty()).then(|| values.iter().sum::<f32>() / values.len() as f32)
}

fn weighted_health(batteries: &[Battery]) -> Option<f32> {
    let mut weighted = 0.0f32;
    let mut weight = 0.0f32;
    for battery in batteries {
        if let (Some(health), Some(design)) = (battery.health_pct, battery.energy_full_design_wh) {
            weighted += health * design;
            weight += design;
        }
    }
    (weight > 0.0).then(|| weighted / weight)
}

fn aggregate_status(batteries: &[Battery]) -> String {
    if batteries.iter().any(|battery| battery.status == "charging") {
        return "charging".into();
    }
    if batteries
        .iter()
        .any(|battery| battery.status == "discharging")
    {
        return "discharging".into();
    }
    if batteries.iter().all(|battery| battery.status == "full") {
        return "full".into();
    }
    if batteries
        .iter()
        .any(|battery| battery.status == "not_charging")
    {
        return "not_charging".into();
    }
    "unknown".into()
}

fn normalize_status(raw: &str) -> String {
    match raw.trim().to_ascii_lowercase().as_str() {
        "charging" => "charging",
        "discharging" => "discharging",
        "full" => "full",
        "not charging" => "not_charging",
        _ => "unknown",
    }
    .to_string()
}

fn micro_wh_to_wh(value: f64) -> f32 {
    (value / 1_000_000.0) as f32
}

fn charge_to_wh(micro_ah: f64, voltage_v: Option<f64>) -> Option<f32> {
    // Ah * V = Wh. Without a voltage the energy cannot be known, but percent
    // and time can still be derived from charge alone elsewhere.
    voltage_v.map(|volts| ((micro_ah / 1_000_000.0) * volts) as f32)
}

fn sum_known(values: impl Iterator<Item = f32>) -> Option<f32> {
    let mut total = 0.0f32;
    let mut found = false;
    for value in values {
        total += value;
        found = true;
    }
    found.then_some(total)
}

fn sum_all(values: impl Iterator<Item = Option<f32>>) -> Option<f32> {
    let mut total = 0.0f32;
    let mut found = false;
    for value in values {
        total += value?;
        found = true;
    }
    found.then_some(total)
}

fn supply_dirs(root: &Path) -> Vec<PathBuf> {
    let Ok(entries) = fs::read_dir(root) else {
        return Vec::new();
    };
    let mut dirs: Vec<PathBuf> = entries
        .flatten()
        .map(|entry| entry.path())
        .filter(|path| path.is_dir())
        .collect();
    dirs.sort();
    dirs
}

fn has_battery_in(root: &Path) -> bool {
    supply_dirs(root).into_iter().any(|dir| {
        read_str(&dir, "type")
            .map(|value| value.eq_ignore_ascii_case("battery"))
            .unwrap_or(false)
            && read_u64(&dir, "present") != Some(0)
    })
}

fn read_str(dir: &Path, attr: &str) -> Option<String> {
    let value = fs::read_to_string(dir.join(attr)).ok()?;
    let value = value.trim();
    (!value.is_empty()).then(|| value.to_string())
}

fn read_f64(dir: &Path, attr: &str) -> Option<f64> {
    read_str(dir, attr)?.parse().ok()
}

fn read_u64(dir: &Path, attr: &str) -> Option<u64> {
    read_str(dir, attr)?.parse().ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU32, Ordering};

    static SEQ: AtomicU32 = AtomicU32::new(0);

    fn fixture() -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "vitals-power-{}-{}",
            std::process::id(),
            SEQ.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn write(dir: &Path, name: &str, attr: &str, value: &str) {
        let supply = dir.join(name);
        std::fs::create_dir_all(&supply).unwrap();
        std::fs::write(supply.join(attr), value).unwrap();
    }

    #[test]
    fn energy_battery_reports_percent_time_and_health() {
        let root = fixture();
        write(&root, "AC", "type", "Mains");
        write(&root, "AC", "online", "0");
        write(&root, "BAT0", "type", "Battery");
        write(&root, "BAT0", "present", "1");
        write(&root, "BAT0", "status", "Discharging");
        write(&root, "BAT0", "capacity", "50");
        write(&root, "BAT0", "energy_now", "25000000");
        write(&root, "BAT0", "energy_full", "50000000");
        write(&root, "BAT0", "energy_full_design", "60000000");
        write(&root, "BAT0", "power_now", "10000000");
        write(&root, "BAT0", "voltage_now", "12000000");
        write(&root, "BAT0", "cycle_count", "100");
        write(&root, "BAT0", "model_name", "TEST-BAT");

        let power = read_from(&root).expect("battery");
        assert!(!power.ac_online);
        assert!(power.on_battery);
        assert_eq!(power.status, "discharging");
        assert_eq!(power.percent, Some(50.0));
        assert!((power.power_w.unwrap() - 10.0).abs() < 0.001);
        assert_eq!(power.seconds_left, Some(9_000));
        assert_eq!(power.cycles, Some(100));
        assert!((power.health_pct.unwrap() - 83.3).abs() < 0.01);
        assert_eq!(power.batteries[0].model.as_deref(), Some("TEST-BAT"));
        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn charge_battery_is_normalised_and_reports_time_to_full() {
        let root = fixture();
        write(&root, "AC", "type", "Mains");
        write(&root, "AC", "online", "1");
        write(&root, "BAT0", "type", "Battery");
        write(&root, "BAT0", "status", "Charging");
        write(&root, "BAT0", "charge_now", "2000000");
        write(&root, "BAT0", "charge_full", "4000000");
        write(&root, "BAT0", "charge_full_design", "5000000");
        write(&root, "BAT0", "voltage_now", "12000000");
        write(&root, "BAT0", "current_now", "2000000");

        let power = read_from(&root).expect("battery");
        assert!(power.ac_online);
        assert!(!power.on_battery);
        assert_eq!(power.status, "charging");
        assert_eq!(power.percent, Some(50.0));
        assert!((power.power_w.unwrap() - 24.0).abs() < 0.01);
        assert_eq!(power.seconds_to_full, Some(3_600));
        assert_eq!(power.seconds_left, None);
        assert!((power.batteries[0].energy_full_wh.unwrap() - 48.0).abs() < 0.01);
        assert!((power.health_pct.unwrap() - 80.0).abs() < 0.01);
        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn charge_only_driver_still_reports_time_left() {
        let root = fixture();
        write(&root, "BAT0", "type", "Battery");
        write(&root, "BAT0", "status", "Discharging");
        write(&root, "BAT0", "capacity", "50");
        write(&root, "BAT0", "charge_now", "2000000");
        write(&root, "BAT0", "charge_full", "4000000");
        write(&root, "BAT0", "current_now", "1000000");

        let power = read_from(&root).expect("battery");
        assert_eq!(power.percent, Some(50.0));
        assert_eq!(power.seconds_left, Some(7_200));
        assert_eq!(power.power_w, None);
        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn ac_only_host_has_no_power_payload() {
        let root = fixture();
        write(&root, "AC", "type", "Mains");
        write(&root, "AC", "online", "1");

        assert!(read_from(&root).is_none());
        assert!(!has_battery_in(&root));
        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn removed_battery_is_ignored() {
        let root = fixture();
        write(&root, "BAT0", "type", "Battery");
        write(&root, "BAT0", "present", "0");

        assert!(read_from(&root).is_none());
        assert!(!has_battery_in(&root));
        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn missing_ac_supply_is_inferred_from_the_battery_state() {
        let root = fixture();
        write(&root, "BAT0", "type", "Battery");
        write(&root, "BAT0", "status", "Full");
        write(&root, "BAT0", "capacity", "100");

        let power = read_from(&root).expect("battery");
        assert!(power.ac_online);
        assert_eq!(power.status, "full");
        std::fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn two_batteries_are_weighted_by_capacity() {
        let root = fixture();
        write(&root, "BAT0", "type", "Battery");
        write(&root, "BAT0", "status", "Discharging");
        write(&root, "BAT0", "capacity", "100");
        write(&root, "BAT0", "energy_full", "50000000");
        write(&root, "BAT1", "type", "Battery");
        write(&root, "BAT1", "status", "Discharging");
        write(&root, "BAT1", "capacity", "0");
        write(&root, "BAT1", "energy_full", "50000000");

        let power = read_from(&root).expect("battery");
        assert_eq!(power.percent, Some(50.0));
        assert_eq!(power.batteries.len(), 2);
        std::fs::remove_dir_all(&root).ok();
    }
}

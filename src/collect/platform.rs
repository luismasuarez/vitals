//! Best-effort classification of the machine, so the app can show a laptop
//! interface only where it makes sense. SMBIOS is the primary source; a
//! visible battery is the fallback when the chassis type is missing.

use std::fs;

use super::power;

const CHASSIS_TYPE: &str = "/sys/class/dmi/id/chassis_type";
const SYS_VENDOR: &str = "/sys/class/dmi/id/sys_vendor";
const PRODUCT_NAME: &str = "/sys/class/dmi/id/product_name";

/// Returns the semantic form factor and whether a hypervisor was detected.
pub fn detect() -> (&'static str, bool) {
    let virtualized = virtualized();
    if virtualized {
        return ("vm", true);
    }
    if let Some(code) = read(CHASSIS_TYPE).and_then(|value| value.parse::<u32>().ok()) {
        if let Some(form_factor) = chassis_form_factor(code) {
            return (form_factor, false);
        }
    }
    if power::has_battery() {
        return ("laptop", false);
    }
    ("unknown", false)
}

/// SMBIOS chassis types that identify a portable or a stationary machine.
/// Unknown and enclosure-only values return `None` on purpose.
pub fn chassis_form_factor(code: u32) -> Option<&'static str> {
    match code {
        8 | 9 | 10 | 14 | 30 | 31 | 32 => Some("laptop"),
        3 | 4 | 5 | 6 | 7 | 13 | 15 | 16 | 24 | 34 | 35 | 36 => Some("desktop"),
        17 | 23 | 25 | 26 | 27 | 28 | 29 => Some("server"),
        _ => None,
    }
}

fn virtualized() -> bool {
    if let Ok(cpuinfo) = fs::read_to_string("/proc/cpuinfo") {
        if cpuinfo
            .lines()
            .filter(|line| line.starts_with("flags"))
            .any(|line| line.split_whitespace().any(|flag| flag == "hypervisor"))
        {
            return true;
        }
    }

    let vendor = read(SYS_VENDOR).unwrap_or_default().to_ascii_lowercase();
    let product = read(PRODUCT_NAME).unwrap_or_default().to_ascii_lowercase();
    const MARKERS: &[&str] = &[
        "qemu",
        "kvm",
        "vmware",
        "virtualbox",
        "innotek",
        "xen",
        "bochs",
        "parallels",
        "openstack",
        "digitalocean",
        "linode",
        "amazon ec2",
        "google compute",
        "microsoft corporation virtual",
        "hyper-v",
    ];
    MARKERS
        .iter()
        .any(|marker| vendor.contains(marker) || product.contains(marker))
}

fn read(path: &str) -> Option<String> {
    let value = fs::read_to_string(path).ok()?;
    let value = value.trim();
    (!value.is_empty()).then(|| value.to_string())
}

#[cfg(test)]
mod tests {
    use super::chassis_form_factor;

    #[test]
    fn laptop_chassis_types_are_portable() {
        for code in [8, 9, 10, 14, 30, 31, 32] {
            assert_eq!(chassis_form_factor(code), Some("laptop"), "code {code}");
        }
    }

    #[test]
    fn desktop_and_server_chassis_types_are_not_portable() {
        for code in [3, 4, 5, 6, 7, 13, 15, 16, 24, 34, 35, 36] {
            assert_eq!(chassis_form_factor(code), Some("desktop"), "code {code}");
        }
        for code in [17, 23, 25, 26, 27, 28, 29] {
            assert_eq!(chassis_form_factor(code), Some("server"), "code {code}");
        }
    }

    #[test]
    fn ambiguous_chassis_types_stay_unknown() {
        for code in [0, 1, 2, 11, 12, 18, 19, 20, 21, 22, 33, 99] {
            assert_eq!(chassis_form_factor(code), None, "code {code}");
        }
    }
}

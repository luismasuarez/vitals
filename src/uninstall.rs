//! Explicit removal of a system or user installation.
//!
//! The command only removes what the installer owns. Tailscale, firewall rules,
//! SSH and the journal are external state and are left alone.

use std::env;
use std::fs;
use std::io::{self, IsTerminal, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, ExitStatus};

use anyhow::{bail, Context, Result};

#[derive(Debug, clap::Args)]
pub struct Args {
    /// Also remove the database, TLS certificate and configuration.
    /// This is irreversible.
    #[arg(long)]
    pub purge: bool,
    /// Do not ask for confirmation.
    #[arg(long)]
    pub yes: bool,
    /// Show what would be removed without changing anything.
    #[arg(long)]
    pub dry_run: bool,
    /// Target the system installation (root only).
    #[arg(long, conflicts_with = "user")]
    pub system: bool,
    /// Target the current user's installation (run without sudo).
    #[arg(long, conflicts_with = "system")]
    pub user: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Mode {
    System,
    User,
}

impl Mode {
    fn label(self) -> &'static str {
        match self {
            Self::System => "sistema",
            Self::User => "usuario",
        }
    }

    fn systemctl(self, args: &[&str]) -> io::Result<ExitStatus> {
        let mut cmd = Command::new("systemctl");
        if self == Self::User {
            cmd.arg("--user");
        }
        cmd.args(args).status()
    }

    /// For best-effort cleanup: a missing unit is expected when uninstalling
    /// twice and should not print a systemd error.
    fn systemctl_quiet(self, args: &[&str]) {
        let mut cmd = Command::new("systemctl");
        if self == Self::User {
            cmd.arg("--user");
        }
        let _ = cmd
            .args(args)
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status();
    }
}

#[derive(Debug)]
struct Layout {
    mode: Mode,
    unit: PathBuf,
    dropins: PathBuf,
    runtime: Option<PathBuf>,
    binary: PathBuf,
    wrapper: PathBuf,
    state: Vec<PathBuf>,
    config: Vec<PathBuf>,
    docs: Vec<PathBuf>,
}

impl Layout {
    fn system() -> Self {
        Self {
            mode: Mode::System,
            unit: PathBuf::from("/etc/systemd/system/vitals.service"),
            dropins: PathBuf::from("/etc/systemd/system/vitals.service.d"),
            runtime: Some(PathBuf::from("/run/vitals")),
            binary: PathBuf::from("/usr/local/bin/vitals"),
            wrapper: PathBuf::from("/usr/local/bin/vitals-update"),
            // DynamicUser stores the real directory under /var/lib/private and
            // leaves a symlink in /var/lib. Remove both.
            state: vec![
                PathBuf::from("/var/lib/private/vitals"),
                PathBuf::from("/var/lib/vitals"),
            ],
            config: vec![PathBuf::from("/etc/vitals")],
            docs: vec![PathBuf::from("/usr/local/share/doc/vitals")],
        }
    }

    fn user(home: &Path, state_home: Option<&Path>, runtime_dir: Option<&Path>) -> Self {
        let state_root = state_home
            .map(Path::to_path_buf)
            .unwrap_or_else(|| home.join(".local/state"));
        let state = state_root.join("vitals");
        // Without XDG_RUNTIME_DIR there is no user runtime directory; do not
        // fall back to the state directory or a normal uninstall would delete
        // the database even without --purge.
        let runtime = runtime_dir.map(|path| path.join("vitals"));
        let config_home = home.join(".config");
        Self {
            mode: Mode::User,
            unit: config_home.join("systemd/user/vitals.service"),
            dropins: config_home.join("systemd/user/vitals.service.d"),
            runtime,
            binary: home.join(".local/bin/vitals"),
            wrapper: home.join(".local/bin/vitals-update"),
            state: vec![state],
            config: Vec::new(),
            docs: Vec::new(),
        }
    }

    /// Paths removed by every uninstall.
    fn base_paths(&self) -> Vec<(&'static str, PathBuf)> {
        let mut paths = vec![
            ("unidad systemd", self.unit.clone()),
            ("wrapper de actualización", self.wrapper.clone()),
            ("binario", self.binary.clone()),
        ];
        if let Some(runtime) = &self.runtime {
            paths.insert(1, ("runtime", runtime.clone()));
        }
        paths
    }

    /// Paths only removed with --purge.
    fn purge_paths(&self) -> Vec<(&'static str, PathBuf)> {
        let mut out = vec![("drop-ins de la unit", self.dropins.clone())];
        for path in &self.config {
            out.push(("configuración", path.clone()));
        }
        for path in &self.state {
            out.push(("datos", path.clone()));
        }
        for path in &self.docs {
            out.push(("documentación", path.clone()));
        }
        out
    }
}

pub fn run(args: &Args) -> Result<()> {
    let mode = detect_mode(args)?;
    let layout = build_layout(mode)?;

    print_plan(&layout, args);
    if args.dry_run {
        println!("\ndry-run: no se ha modificado nada.");
        return Ok(());
    }

    if !args.yes {
        confirm()?;
    }

    stop_service(mode)?;

    let mut failures: Vec<(String, anyhow::Error)> = Vec::new();
    let mut removed: Vec<String> = Vec::new();

    for (label, path) in layout.base_paths() {
        match remove_path(&path) {
            Ok(true) => removed.push(format!("{label}: {}", path.display())),
            Ok(false) => {}
            Err(error) => failures.push((format!("{label}: {}", path.display()), error)),
        }
    }
    if args.purge {
        for (label, path) in layout.purge_paths() {
            match remove_path(&path) {
                Ok(true) => removed.push(format!("{label}: {}", path.display())),
                Ok(false) => {}
                Err(error) => failures.push((format!("{label}: {}", path.display()), error)),
            }
        }
    }

    reload_systemd(mode);

    println!();
    for line in &removed {
        println!("  borrado {line}");
    }
    if !args.purge {
        println!("\nDatos y configuración conservados:");
        for path in layout.state.iter().chain(layout.config.iter()) {
            println!("  {}", path.display());
        }
        let prefix = if mode == Mode::System { "sudo " } else { "" };
        let paths: Vec<String> = layout
            .state
            .iter()
            .chain(layout.config.iter())
            .map(|path| path.display().to_string())
            .collect();
        println!("\nPara borrarlos:");
        println!("  {prefix}rm -rf {}", paths.join(" "));
    }

    println!("\nNo se ha tocado Tailscale, UFW, SSH ni el journal.");
    println!("Si añadiste la regla de UFW:");
    println!("  sudo ufw delete allow in on tailscale0 to any port 8443 proto tcp");
    println!("Si la app estaba emparejada, usa Ajustes → Olvidar este host.");

    if !failures.is_empty() {
        eprintln!("\nNo se pudieron borrar estos elementos:");
        for (what, error) in &failures {
            eprintln!("  {what}: {error:#}");
        }
        bail!("desinstalación incompleta; revisa las rutas anteriores");
    }

    println!("\nvitals desinstalado ({}).", mode.label());
    Ok(())
}

fn detect_mode(args: &Args) -> Result<Mode> {
    if args.system {
        if !args.dry_run && !is_root() {
            bail!("el modo --system necesita root; usa sudo");
        }
        return Ok(Mode::System);
    }
    if args.user {
        if is_root() {
            bail!("--user debe ejecutarse sin sudo, como el usuario que hizo la instalación");
        }
        return Ok(Mode::User);
    }

    let root = is_root();
    let system = Path::new("/etc/systemd/system/vitals.service").exists();
    let user = user_unit_exists();

    // Do not let a non-root user "uninstall" an untouched user directory while
    // the real system install stays behind.
    if !root && system && !user {
        if args.dry_run {
            return Ok(Mode::System);
        }
        bail!("hay una instalación de sistema; ejecuta con sudo");
    }
    if root && user && !system {
        bail!("hay una instalación de usuario; ejecútalo sin sudo como ese usuario");
    }
    if root {
        Ok(Mode::System)
    } else {
        Ok(Mode::User)
    }
}

fn user_unit_exists() -> bool {
    env::var_os("HOME")
        .map(PathBuf::from)
        .map(|home| home.join(".config/systemd/user/vitals.service").exists())
        .unwrap_or(false)
}

fn build_layout(mode: Mode) -> Result<Layout> {
    match mode {
        Mode::System => Ok(Layout::system()),
        Mode::User => {
            let home = env::var_os("HOME")
                .map(PathBuf::from)
                .filter(|path| !path.as_os_str().is_empty())
                .context("HOME no está definido; no puedo localizar la instalación de usuario")?;
            let state_home = env::var_os("XDG_STATE_HOME").map(PathBuf::from);
            let runtime_dir = env::var_os("XDG_RUNTIME_DIR").map(PathBuf::from);
            Ok(Layout::user(
                &home,
                state_home.as_deref(),
                runtime_dir.as_deref(),
            ))
        }
    }
}

fn print_plan(layout: &Layout, args: &Args) {
    println!("vitals uninstall ({})", layout.mode.label());
    for (label, path) in layout.base_paths() {
        println!("  {label:<24} {}", path.display());
    }
    if args.purge {
        for (label, path) in layout.purge_paths() {
            println!("  {label:<24} {}", path.display());
        }
        println!("\n--purge: los datos, el certificado y los tokens se borrarán.");
    } else {
        println!("\nDatos y configuración se conservan; usa --purge para borrarlos.");
    }
}

fn confirm() -> Result<()> {
    if !io::stdin().is_terminal() {
        bail!("no hay terminal para confirmar; vuelve a ejecutar con --yes");
    }
    print!("\n¿Continuar? [y/N] ");
    io::stdout().flush().ok();
    let mut line = String::new();
    io::stdin().read_line(&mut line)?;
    if matches!(
        line.trim().to_lowercase().as_str(),
        "y" | "yes" | "s" | "si" | "sí"
    ) {
        Ok(())
    } else {
        bail!("cancelado")
    }
}

fn stop_service(mode: Mode) -> Result<()> {
    // If systemd is absent, is-active fails; the files are still removable.
    let active = mode
        .systemctl(&["is-active", "--quiet", "vitals"])
        .map(|status| status.success())
        .unwrap_or(false);
    if active {
        let status = mode
            .systemctl(&["disable", "--now", "vitals"])
            .context("no se pudo ejecutar systemctl")?;
        if !status.success() {
            bail!("no se pudo parar vitals; revisa systemctl status vitals");
        }
    } else {
        // Remove the enable symlink even if the service is already stopped.
        mode.systemctl_quiet(&["disable", "vitals"]);
    }
    Ok(())
}

fn reload_systemd(mode: Mode) {
    mode.systemctl_quiet(&["daemon-reload"]);
    mode.systemctl_quiet(&["reset-failed", "vitals"]);
}

/// Returns false when the path was already gone, so a second uninstall stays
/// quiet instead of claiming it deleted files that never existed.
fn remove_path(path: &Path) -> Result<bool> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_dir() => {
            fs::remove_dir_all(path)
                .with_context(|| format!("no se pudo borrar el directorio {}", path.display()))?;
            Ok(true)
        }
        Ok(_) => {
            fs::remove_file(path)
                .with_context(|| format!("no se pudo borrar {}", path.display()))?;
            Ok(true)
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(false),
        Err(error) => Err(error).with_context(|| format!("no se pudo leer {}", path.display())),
    }
}

fn is_root() -> bool {
    use std::os::unix::fs::MetadataExt;
    fs::metadata("/proc/self")
        .map(|metadata| metadata.uid() == 0)
        .unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn system_layout_matches_the_unit_and_installer_paths() {
        let layout = Layout::system();
        assert_eq!(layout.mode, Mode::System);
        assert_eq!(
            layout.unit,
            PathBuf::from("/etc/systemd/system/vitals.service")
        );
        assert_eq!(layout.binary, PathBuf::from("/usr/local/bin/vitals"));
        assert_eq!(
            layout.wrapper,
            PathBuf::from("/usr/local/bin/vitals-update")
        );
        assert_eq!(layout.runtime, Some(PathBuf::from("/run/vitals")));
        assert!(layout
            .state
            .contains(&PathBuf::from("/var/lib/private/vitals")));
        assert!(layout.state.contains(&PathBuf::from("/var/lib/vitals")));
        assert_eq!(layout.config, vec![PathBuf::from("/etc/vitals")]);
    }

    #[test]
    fn user_layout_uses_xdg_directories_when_present() {
        let home = Path::new("/home/alice");
        let layout = Layout::user(
            home,
            Some(Path::new("/home/alice/.state")),
            Some(Path::new("/run/user/1000")),
        );
        assert_eq!(layout.mode, Mode::User);
        assert_eq!(
            layout.unit,
            PathBuf::from("/home/alice/.config/systemd/user/vitals.service")
        );
        assert_eq!(
            layout.binary,
            PathBuf::from("/home/alice/.local/bin/vitals")
        );
        assert_eq!(layout.runtime, Some(PathBuf::from("/run/user/1000/vitals")));
        assert_eq!(
            layout.state,
            vec![PathBuf::from("/home/alice/.state/vitals")]
        );
        assert!(layout.config.is_empty());
        assert!(layout.docs.is_empty());
    }

    #[test]
    fn user_layout_falls_back_to_home_and_state() {
        let home = Path::new("/home/alice");
        let layout = Layout::user(home, None, None);
        assert_eq!(
            layout.state,
            vec![PathBuf::from("/home/alice/.local/state/vitals")]
        );
        assert_eq!(layout.runtime, None);
    }

    #[test]
    fn purge_only_adds_data_and_configuration() {
        let layout = Layout::system();
        let base: Vec<PathBuf> = layout
            .base_paths()
            .into_iter()
            .map(|(_, path)| path)
            .collect();
        let purge: Vec<PathBuf> = layout
            .purge_paths()
            .into_iter()
            .map(|(_, path)| path)
            .collect();
        assert!(purge.contains(&PathBuf::from("/etc/vitals")));
        assert!(purge.contains(&PathBuf::from("/var/lib/vitals")));
        assert!(!base.contains(&PathBuf::from("/etc/vitals")));
        assert!(!base.contains(&PathBuf::from("/var/lib/vitals")));
    }

    #[test]
    fn removing_a_symlink_does_not_remove_its_target() {
        let root = std::env::temp_dir().join(format!(
            "vitals-uninstall-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(&root).unwrap();
        let target = root.join("target");
        fs::write(&target, b"keep").unwrap();
        let link = root.join("link");
        std::os::unix::fs::symlink(&target, &link).unwrap();

        assert!(remove_path(&link).unwrap());
        assert!(!link.exists());
        assert!(target.exists());

        assert!(remove_path(&target).unwrap());
        fs::remove_dir_all(&root).ok();
    }

    #[test]
    fn removing_a_missing_path_is_idempotent() {
        let path = std::env::temp_dir().join(format!(
            "vitals-uninstall-missing-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        assert!(!remove_path(&path).unwrap());
        assert!(!remove_path(&path).unwrap());
    }
}

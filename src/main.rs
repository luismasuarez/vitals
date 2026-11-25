mod alerts;
mod api;
mod auth;
mod caps;
mod cli;
mod collect;
mod config;
mod control;
mod diagnostics;
mod net;
mod store;
mod tls;

use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant};

use anyhow::{bail, Result};
use axum_server::tls_rustls::RustlsConfig;
use clap::{Parser, Subcommand};
use sysinfo::System;
use tokio::sync::broadcast;
use tracing::{info, warn};
use tracing_subscriber::EnvFilter;

use api::{AppState, HostInfo};
use collect::{ProcSampler, Sample, Sampler};
use config::{Config, Paths};
use net::Kind;
use store::Store;

#[derive(Parser)]
#[command(
    name = "vitals",
    version,
    about = "Read-only host metrics API over pinned TLS"
)]
struct Cli {
    /// Config file path; a missing file means built-in defaults.
    #[arg(long, global = true, default_value = "/etc/vitals/config.toml")]
    config: PathBuf,
    /// Override the state directory (dev runs).
    #[arg(long, global = true)]
    state_dir: Option<PathBuf>,
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Run the HTTPS agent.
    Serve,
    /// Issue a single-use pairing code and print its QR.
    Pair {
        #[arg(long)]
        name: Option<String>,
        #[arg(long, default_value_t = 300, value_parser = clap::value_parser!(u64).range(60..=900))]
        ttl: u64,
        /// Issue an operator token: metrics plus command and audit.
        #[arg(long)]
        operator: bool,
    },
    /// Manage paired devices.
    Devices {
        #[command(subcommand)]
        cmd: DevicesCmd,
    },
    /// Inspect the TLS pin and endpoints.
    Tls {
        #[command(subcommand)]
        cmd: TlsCmd,
    },
    /// Approve or revoke capabilities. Must run as root (system install) or
    /// as the service owner (user install).
    Caps {
        #[command(subcommand)]
        cmd: CapsCmd,
    },
}

#[derive(Subcommand)]
enum DevicesCmd {
    List,
    Revoke { id: String },
}

#[derive(Subcommand)]
enum TlsCmd {
    Show,
}

#[derive(Subcommand)]
enum CapsCmd {
    /// List approved grants in the manifest.
    List,
    /// List requests waiting for approval.
    Pending,
    /// Approve by list number or by the app's 6-character code.
    Approve {
        target: String,
        /// Do not ask for confirmation.
        #[arg(long)]
        yes: bool,
    },
    /// Remove a grant from one device.
    Revoke { device_id: String, cap: String },
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("vitals=info")),
        )
        .with_target(false)
        .init();

    let cli = Cli::parse();
    let paths = Paths::resolve(cli.state_dir.as_deref())?;
    let sockets = Paths::control_candidates(cli.state_dir.as_deref());

    match cli.cmd {
        Cmd::Serve => {
            let cfg = Config::load(&cli.config)?;
            serve(cfg, paths).await
        }
        Cmd::Pair {
            name,
            ttl,
            operator,
        } => cli::pair(&sockets, name.as_deref(), ttl, operator),
        Cmd::Devices { cmd } => match cmd {
            DevicesCmd::List => cli::devices(&sockets),
            DevicesCmd::Revoke { id } => cli::revoke(&sockets, &id),
        },
        Cmd::Tls { cmd } => match cmd {
            TlsCmd::Show => cli::info(&sockets),
        },
        Cmd::Caps { cmd } => match cmd {
            CapsCmd::List => cli::caps_list(&sockets),
            CapsCmd::Pending => cli::caps_pending(&sockets),
            CapsCmd::Approve { target, yes } => cli::caps_approve(&sockets, &target, yes),
            CapsCmd::Revoke { device_id, cap } => cli::caps_revoke(&sockets, &device_id, &cap),
        },
    }
}

async fn serve(cfg: Config, paths: Paths) -> Result<()> {
    let store = Arc::new(Store::open(&paths.db)?);

    let candidates = net::candidates(
        cfg.bind_mode,
        cfg.allow_public,
        &cfg.extra_bind_addrs,
        cfg.bind_ipv6,
    )?;
    if candidates.is_empty() {
        bail!(
            "no bindable address: check bind_mode and allow_public in {}",
            paths.state_dir.display()
        );
    }

    let hostname = System::host_name().unwrap_or_else(|| "vitals".into());
    let mut dns = vec![hostname.clone(), format!("{hostname}.local")];
    if let Some(name) = net::tailscale_dns_suffix() {
        dns.push(name);
    }
    let ips: Vec<IpAddr> = candidates.iter().map(|c| c.addr).collect();
    let generated = tls::ensure(&paths, &dns, &ips)?;
    let pin = tls::pin(&paths)?;
    if generated {
        info!("generated a new TLS certificate; run `vitals pair` when the app is ready");
    }

    // The address set changes over time (DHCP, Wi-Fi roam, VPN up/down), so the
    // supervisor below recomputes it and rewrites this list.
    let endpoints: Arc<std::sync::RwLock<Vec<String>>> =
        Arc::new(std::sync::RwLock::new(Vec::new()));

    let host = build_host_info(&hostname);
    let (tx, _) = broadcast::channel::<Arc<Sample>>(16);
    let last: Arc<Mutex<Option<Arc<Sample>>>> = Arc::new(Mutex::new(None));
    let access = Arc::new(RwLock::new(None));

    let state = AppState {
        store: store.clone(),
        tx: tx.clone(),
        last: last.clone(),
        access: access.clone(),
        pin: Arc::new(pin.clone()),
        endpoints: endpoints.clone(),
        host: Arc::new(host),
        limiter: Arc::new(auth::RateLimiter::default()),
        cmd_limiter: Arc::new(auth::DeviceLimiter::default()),
        procs: Arc::new(Mutex::new(ProcSampler::new())),
        caps_manifest: Arc::new(paths.caps_manifest.clone()),
        state_dir: Arc::new(paths.state_dir.clone()),
        started: Instant::now(),
    };

    spawn_sampler(cfg.clone(), store.clone(), tx, last, access);

    let tls_config = RustlsConfig::from_pem_file(&paths.cert, &paths.key).await?;
    let app = api::router(state.clone());

    tokio::spawn(supervise_listeners(
        cfg.clone(),
        tls_config,
        app,
        endpoints.clone(),
    ));

    let sock_paths = paths.clone();
    let sock_state = state.clone();
    tokio::spawn(async move {
        if let Err(e) = control::serve(&sock_paths, sock_state).await {
            warn!(error = %e, "control socket stopped");
        }
    });

    info!(pin = %pin, port = cfg.port, state = %paths.state_dir.display(), "vitals ready");

    shutdown_signal().await;
    info!("shutting down");
    Ok(())
}

fn build_host_info(hostname: &str) -> HostInfo {
    let sys = System::new_all();
    let (form_factor, virtualized) = collect::platform::detect();
    HostInfo {
        hostname: hostname.to_string(),
        os: format!(
            "{} {}",
            System::name().unwrap_or_else(|| "Linux".into()),
            System::os_version().unwrap_or_default()
        )
        .trim()
        .to_string(),
        kernel: System::kernel_version().unwrap_or_default(),
        arch: std::env::consts::ARCH.to_string(),
        boot_time: System::boot_time(),
        cpu_model: sys
            .cpus()
            .first()
            .map(|c| c.brand().to_string())
            .unwrap_or_default(),
        cores: sys.cpus().len(),
        mem_total: sys.total_memory(),
        form_factor: form_factor.to_string(),
        virtualized,
    }
}

/// One collector, two outputs: every tick goes to SSE subscribers, every
/// `persist_every` ticks goes to SQLite.
fn spawn_sampler(
    cfg: Config,
    store: Arc<Store>,
    tx: broadcast::Sender<Arc<Sample>>,
    last: Arc<Mutex<Option<Arc<Sample>>>>,
    access: Arc<RwLock<Option<Arc<collect::access::AccessSnapshot>>>>,
) {
    tokio::spawn(async move {
        let mut sampler = Sampler::new();
        let mut access_collector = collect::access::Collector::new();
        let interval = Duration::from_millis(cfg.sample_interval_ms.clamp(250, 60_000));
        let mut ticker = tokio::time::interval(interval);
        ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

        let persist_every = cfg.persist_every.max(1) as u64;
        let mut ticks: u64 = 0;
        let mut last_maintenance = Instant::now();
        let mut last_disk_record = Instant::now() - Duration::from_secs(60);
        let mut evaluator = alerts::Evaluator::new();

        loop {
            ticker.tick().await;
            if let Some(snapshot) = access_collector.tick() {
                if let Ok(mut slot) = access.write() {
                    *slot = Some(snapshot);
                }
            }
            let mut sample = sampler.tick();
            sample.access = access
                .read()
                .ok()
                .and_then(|slot| slot.as_ref().map(|snapshot| snapshot.summary()));
            let sample = Arc::new(sample);
            if let Ok(mut slot) = last.lock() {
                *slot = Some(sample.clone());
            }
            let _ = tx.send(sample.clone());

            // One row per mount per minute feeds the capacity prediction.
            if last_disk_record.elapsed() >= Duration::from_secs(60) {
                last_disk_record = Instant::now();
                let minute = (sample.t as i64 / 60) * 60;
                let rows: Vec<(String, i64, i64, i64)> = sample
                    .disks
                    .iter()
                    .map(|disk| {
                        (
                            disk.mount.clone(),
                            minute,
                            disk.used as i64,
                            disk.total as i64,
                        )
                    })
                    .collect();
                if let Err(e) = store.record_disk_usage(&rows) {
                    warn!(error = %e, "cannot record disk usage");
                }
            }

            evaluator.tick(&store, &sample);

            ticks += 1;
            if ticks % persist_every == 0 {
                if let Err(e) = store.insert_raw(&sample.to_point()) {
                    warn!(error = %e, "cannot persist sample");
                }
            }

            if last_maintenance.elapsed() >= Duration::from_secs(60) {
                last_maintenance = Instant::now();
                let now = store::now_ts();
                if let Err(e) = store.rollup(now) {
                    warn!(error = %e, "rollup failed");
                }
                if let Err(e) = store.prune(now, cfg.retention_raw_hours, cfg.retention_1m_days) {
                    warn!(error = %e, "prune failed");
                }
                if let Err(e) = store.prune_audit(now, 10_000) {
                    warn!(error = %e, "audit prune failed");
                }
                if let Err(e) = store.prune_alerts(now) {
                    warn!(error = %e, "alert prune failed");
                }
            }
        }
    });
}

type BoundListener = (axum_server::Handle<SocketAddr>, tokio::task::JoinHandle<()>);

fn endpoint_list(addrs: &[IpAddr], port: u16) -> Vec<String> {
    let mut list: Vec<String> = addrs
        .iter()
        .filter(|addr| net::classify(**addr) != Kind::Loopback)
        .map(|addr| format!("https://{}:{}", net::url_host(*addr), port))
        .collect();
    list.sort();
    list.dedup();
    list
}

/// Keeps listeners in sync with the interfaces that currently have an address.
/// Binding once at startup left the agent stuck on whatever was up at that
/// moment (for example only tailscale0 while Wi-Fi was still associating).
async fn supervise_listeners(
    cfg: Config,
    tls: RustlsConfig,
    app: axum::Router,
    endpoints: Arc<std::sync::RwLock<Vec<String>>>,
) {
    let mut active: std::collections::HashMap<IpAddr, BoundListener> =
        std::collections::HashMap::new();
    let mut last_endpoints: Vec<String> = Vec::new();

    loop {
        let candidates = net::candidates(
            cfg.bind_mode,
            cfg.allow_public,
            &cfg.extra_bind_addrs,
            cfg.bind_ipv6,
        )
        .unwrap_or_default();
        let wanted: Vec<IpAddr> = candidates.iter().map(|c| c.addr).collect();
        let current: Vec<IpAddr> = active.keys().copied().collect();
        let (stop, start) = net::plan_changes(&current, &wanted);

        for addr in stop {
            if let Some((handle, task)) = active.remove(&addr) {
                handle.shutdown();
                task.abort();
                info!(%addr, "stopped listening");
            }
        }

        for addr in start {
            let socket = SocketAddr::new(addr, cfg.port);
            let iface = candidates
                .iter()
                .find(|c| c.addr == addr)
                .map(|c| c.iface.clone())
                .unwrap_or_default();
            let listener = match std::net::TcpListener::bind(socket) {
                Ok(listener) => listener,
                Err(e) => {
                    warn!(%socket, %iface, error = %e, "cannot bind");
                    continue;
                }
            };

            let handle = axum_server::Handle::new();
            let task_handle = handle.clone();
            let task_app = app.clone();
            let task_tls = tls.clone();
            let task = tokio::spawn(async move {
                info!(addr = %socket, %iface, "listening");
                match axum_server::from_tcp_rustls(listener, task_tls) {
                    Ok(server) => {
                        if let Err(e) = server
                            .handle(task_handle)
                            .serve(task_app.into_make_service_with_connect_info::<SocketAddr>())
                            .await
                        {
                            warn!(%socket, error = %e, "listener stopped");
                        }
                    }
                    Err(e) => warn!(%socket, error = %e, "cannot start listener"),
                }
            });
            active.insert(addr, (handle, task));
        }

        let list = endpoint_list(&wanted, cfg.port);
        if list != last_endpoints {
            if let Ok(mut slot) = endpoints.write() {
                *slot = list.clone();
            }
            last_endpoints = list.clone();
            if list.is_empty() {
                warn!("sin direcciones utilizables: sólo loopback hasta que aparezca una interfaz");
            } else {
                info!(?list, "pairing endpoints");
            }
        }

        tokio::time::sleep(Duration::from_secs(5)).await;
    }
}

async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    let terminate = async {
        match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            Ok(mut sig) => {
                sig.recv().await;
            }
            Err(_) => std::future::pending::<()>().await,
        }
    };
    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}

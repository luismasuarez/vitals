#!/usr/bin/env bash
# vitals installer / updater (Linux).
#
#   curl -fsSL https://raw.githubusercontent.com/luismasuarez/vitals/main/scripts/install.sh | sh
#
# Re-running it updates an existing install: same version is a no-op,
# a newer one replaces the binary and restarts the service. Config and data
# are never touched.
#
# Flags:
#   --system        install for the whole machine (needs root)
#   --user          install for the current user (default when not root)
#   --no-service    install the binary only; no systemd unit
#   --version X.Y.Z install a specific version (default: latest)
#   --check         print installed vs latest and exit
#   --force         reinstall even if the version matches
#   -h, --help
set -euo pipefail

REPO="${VITALS_REPO:-luismasuarez/vitals}"
RAW="https://raw.githubusercontent.com/${REPO}/main/scripts/install.sh"

VERSION="latest"
MODE="auto"
SERVICE="yes"
FORCE="no"
CHECK="no"

usage() {
  sed -n '2,18p' "$0" 2>/dev/null || true
  cat <<'TXT'

Examples:
  curl -fsSL https://raw.githubusercontent.com/luismasuarez/vitals/main/scripts/install.sh | sh
  sudo ./install.sh --system
  ./install.sh --no-service
  ./install.sh --version 0.1.0
TXT
}

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
info() { printf '%s\n' "$*"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --system) MODE="system" ;;
    --user) MODE="user" ;;
    --no-service) SERVICE="no" ;;
    --version) shift; VERSION="${1:-}" ;;
    --version=*) VERSION="${1#*=}" ;;
    --check) CHECK="yes" ;;
    --force) FORCE="yes" ;;
    -h|--help) usage; exit 0 ;;
    *) die "opción desconocida: $1 (usa --help)" ;;
  esac
  shift
done

[[ "$(uname -s)" == "Linux" ]] || die "solo Linux por ahora"

case "$(uname -m)" in
  x86_64|amd64) TARGET="x86_64-unknown-linux-musl" ;;
  aarch64|arm64) TARGET="aarch64-unknown-linux-musl" ;;
  *) die "arquitectura no soportada: $(uname -m)" ;;
esac

if [[ "${MODE}" == "auto" ]]; then
  if [[ "$(id -u)" -eq 0 ]]; then MODE="system"; else MODE="user"; fi
fi

if [[ "${MODE}" == "system" ]]; then
  [[ "$(id -u)" -eq 0 ]] || die "el modo --system necesita root (usa sudo)"
  BIN="/usr/local/bin/vitals"
else
  BIN="${HOME}/.local/bin/vitals"
fi

command -v curl >/dev/null || die "falta curl"
command -v tar >/dev/null || die "falta tar"
command -v sha256sum >/dev/null || die "falta sha256sum"

latest_version() {
  curl -fsSL "https://api.github.com/repos/${REPO}/releases/latest" \
    | sed -n 's/^[[:space:]]*"tag_name":[[:space:]]*"v\{0,1\}\([^"]*\)".*/\1/p' \
    | head -n1
}

if [[ "${VERSION}" == "latest" ]]; then
  VERSION="$(latest_version)"
  [[ -n "${VERSION}" ]] || die "no pude resolver la última versión en GitHub"
fi
VERSION="${VERSION#v}"

CURRENT=""
if [[ -x "${BIN}" ]]; then
  CURRENT="$("${BIN}" --version 2>/dev/null | awk '{print $NF}')" || CURRENT=""
fi

if [[ "${CHECK}" == "yes" ]]; then
  info "instalado: ${CURRENT:-ninguno}"
  info "disponible: ${VERSION}"
  exit 0
fi

if [[ "${CURRENT}" == "${VERSION}" && "${FORCE}" == "no" ]]; then
  info "vitals ${VERSION} ya está instalado en ${BIN}"
else
  NAME="vitals-${VERSION}-${TARGET}"
  BASE="https://github.com/${REPO}/releases/download/v${VERSION}"
  TMP="$(mktemp -d)"
  trap 'rm -rf "${TMP}"' EXIT

  info "==> descargando vitals ${VERSION} (${TARGET})"
  curl -fsSL --retry 3 --retry-delay 2 "${BASE}/${NAME}.tar.gz" -o "${TMP}/${NAME}.tar.gz"
  curl -fsSL --retry 3 --retry-delay 2 "${BASE}/SHA256SUMS" -o "${TMP}/SHA256SUMS"

  ( cd "${TMP}" && grep -E "[ *]${NAME}\.tar\.gz$" SHA256SUMS | sha256sum -c - >/dev/null ) \
    || die "la suma de verificación no coincide"

  tar xzf "${TMP}/${NAME}.tar.gz" -C "${TMP}"
  SRC="${TMP}/${NAME}/vitals"
  [[ -x "${SRC}" ]] || die "el archivo no contiene el binario"

  info "==> instalando en ${BIN}"
  install -d "$(dirname "${BIN}")"
  install -m 0755 "${SRC}" "${BIN}.new"
  mv -f "${BIN}.new" "${BIN}"

  if [[ "${MODE}" == "system" ]]; then
    install -d /etc/vitals
    if [[ ! -f /etc/vitals/config.toml ]]; then
      install -m 0644 "${TMP}/${NAME}/config.toml.example" /etc/vitals/config.toml
      info "    configuración de ejemplo en /etc/vitals/config.toml"
    fi
    install -m 0644 "${TMP}/${NAME}/vitals.service" /etc/systemd/system/vitals.service
  fi
fi

# Update helper: one command, always the latest installer logic.
WRAPPER="$(dirname "${BIN}")/vitals-update"
cat > "${WRAPPER}" <<EOF
#!/usr/bin/env bash
set -euo pipefail
exec curl -fsSL ${RAW} | sh -s -- "\$@"
EOF
chmod 0755 "${WRAPPER}"

if [[ "${SERVICE}" == "yes" ]]; then
  if [[ "${MODE}" == "system" ]]; then
    systemctl daemon-reload
    systemctl enable vitals >/dev/null 2>&1 || true
    systemctl restart vitals
    info "==> servicio systemd reiniciado (systemctl status vitals)"
  elif command -v systemctl >/dev/null && systemctl --user show-environment >/dev/null 2>&1; then
    install -d "${HOME}/.config/systemd/user"
    cat > "${HOME}/.config/systemd/user/vitals.service" <<'UNIT'
[Unit]
Description=vitals host metrics agent
After=network-online.target

[Service]
Type=simple
ExecStart=%h/.local/bin/vitals serve
Environment=RUST_LOG=vitals=info
Restart=on-failure
RestartSec=3
StateDirectory=vitals
RuntimeDirectory=vitals
RuntimeDirectoryMode=0700
UMask=0077
NoNewPrivileges=yes
PrivateTmp=yes
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
RestrictSUIDSGID=yes

[Install]
WantedBy=default.target
UNIT
    systemctl --user daemon-reload
    systemctl --user enable vitals >/dev/null 2>&1 || true
    systemctl --user restart vitals
    info "==> servicio de usuario reiniciado (systemctl --user status vitals)"
  else
    info "AVISO: sin sesión systemd de usuario; arranca el agente con: vitals serve"
  fi
fi

# Wait for the admin socket so the next command works immediately.
for _ in $(seq 1 25); do
  if "${BIN}" tls show >/dev/null 2>&1; then break; fi
  sleep 0.2
done

info ""
if "${BIN}" tls show >/dev/null 2>&1; then
  info "vitals ${VERSION} listo y en marcha."
else
  info "vitals ${VERSION} instalado, pero el agente aún no responde."
  info "Revisa el log: systemctl --user status vitals  (o systemctl status vitals)"
fi

if [[ "${MODE}" == "system" ]]; then
  info ""
  info "Empareja tu teléfono con:   sudo vitals pair --name \"mi-telefono\""
  info "Actualiza más tarde con:    sudo vitals-update"
else
  info ""
  info "Empareja tu teléfono con:   vitals pair --name \"mi-telefono\""
  info "Actualiza más tarde con:    vitals-update"
  case ":${PATH}:" in
    *":${HOME}/.local/bin:"*) ;;
    *) info "NOTA: añade ${HOME}/.local/bin al PATH para usar 'vitals' desde cualquier sitio";;
  esac
fi

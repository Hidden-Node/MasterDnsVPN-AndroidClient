#!/usr/bin/env bash

set -euo pipefail
IFS=$'\n\t'

RED='\033[1;31m'
GREEN='\033[1;32m'
YELLOW='\033[1;33m'
BLUE='\033[1;34m'
MAGENTA='\033[1;35m'
CYAN='\033[1;36m'
BOLD='\033[1m'
NC='\033[0m'

log_header() { echo -e "\n${CYAN}${BOLD}>>> $1${NC}"; }
log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[DONE]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }
require_cmd() { command -v "$1" >/dev/null 2>&1 || log_error "Missing command: $1"; }
backup_file_once() {
  local f="$1"
  [[ -f "$f" && ! -f "${f}.bak" ]] && cp -a "$f" "${f}.bak"
}
extract_config_version() {
  local f="$1"
  [[ -f "$f" ]] || return 0
  grep '^CONFIG_VERSION' "$f" | awk -F'=' '{print $2}' | tr -d ' "' | head -n1
}
version_lt() {
  [[ "$1" == "$2" ]] && return 1
  [[ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -n1)" == "$1" ]]
}

if [[ "${EUID}" -ne 0 ]]; then
  log_error "Run this script as root (sudo)."
fi

INSTALL_DIR="$(pwd -P)"
[[ -n "${PWD:-}" ]] && INSTALL_DIR="$PWD"
if [[ "$INSTALL_DIR" == /dev/fd* || "$INSTALL_DIR" == /proc/*/fd* ]]; then
  INSTALL_DIR="$(pwd -P)"
fi
log_info "Installation directory: $INSTALL_DIR"
cd "$INSTALL_DIR" || log_error "Cannot access install directory: $INSTALL_DIR"
if [[ -f "server_config.toml" && -f "server_config.toml.backup" ]]; then
  log_error "Both server_config.toml and server_config.toml.backup exist. Remove one and retry."
fi

if [[ -f /etc/os-release ]]; then
  # shellcheck disable=SC1091
  . /etc/os-release
else
  log_error "OS detection failed (/etc/os-release missing)."
fi

echo -e "${MAGENTA}${BOLD}"
echo "  __  __           _             _____  _   _  _____ "
echo " |  \/  |         | |           |  __ \| \ | |/ ____|"
echo " | \  / | __ _ ___| |_ ___ _ __ | |  | |  \| | (___  "
echo " | |\/| |/ _\` / __| __/ _ \ '__|| |  | | . \ |\___ \ "
echo " | |  | | (_| \__ \ ||  __/ |   | |__| | |\  |____) |"
echo " |_|  |_|\__,_|___/\__\___|_|   |_____/|_| \_|_____/ "
echo -e "           MasterDnsVPN Server Auto-Installer${NC}"
echo -e "${CYAN}------------------------------------------------------${NC}"

TMP_LOG="init_logs.tmp"
DOWNLOAD_DIR=""
cleanup() {
  rm -f "$TMP_LOG" 2>/dev/null || true
  if [[ -n "${DOWNLOAD_DIR:-}" && -d "${DOWNLOAD_DIR:-}" ]]; then
    rm -rf "$DOWNLOAD_DIR" 2>/dev/null || true
  fi
}
trap cleanup EXIT

PM=""
if command -v apt-get >/dev/null 2>&1; then PM="apt";
elif command -v dnf >/dev/null 2>&1; then PM="dnf";
elif command -v yum >/dev/null 2>&1; then PM="yum";
else log_error "No supported package manager found (apt/dnf/yum)."; fi

log_header "Preparing Environment"
log_info "Installing dependencies..."
if [[ "$PM" == "apt" ]]; then
  apt-get update -y >/dev/null 2>&1
  apt-get install -y lsof net-tools wget unzip curl ca-certificates iproute2 procps >/dev/null 2>&1
elif [[ "$PM" == "dnf" ]]; then
  dnf -y install lsof net-tools wget unzip curl ca-certificates iproute procps-ng >/dev/null 2>&1
else
  yum -y install lsof net-tools wget unzip curl ca-certificates iproute procps-ng >/dev/null 2>&1
fi
require_cmd ss
require_cmd unzip
require_cmd systemctl
require_cmd sysctl
log_success "System tools are ready."

check_port53() {
  ss -H -lun "sport = :53" 2>/dev/null | grep -q ':53' && return 0
  ss -H -ltn "sport = :53" 2>/dev/null | grep -q ':53' && return 0
  return 1
}

get_port53_pids() {
  local pids
  pids="$(ss -H -lupn "sport = :53" 2>/dev/null | sed -n 's/.*pid=\([0-9]\+\).*/\1/p' | sort -u)"
  if [[ -n "$pids" ]]; then
    echo "$pids"
    return 0
  fi
  lsof -ti :53 2>/dev/null || true
}

log_header "Managing Network Ports (Port 53)"
if systemctl list-unit-files | grep -q '^masterdnsvpn\.service'; then
  log_info "Stopping existing MasterDnsVPN service..."
  systemctl stop masterdnsvpn 2>/dev/null || true
fi

if check_port53; then
  log_warn "Port 53 is occupied. Trying auto-cleanup..."

  if systemctl is-active --quiet systemd-resolved; then
    log_info "Configuring systemd-resolved DNSStubListener=no ..."
    backup_file_once /etc/systemd/resolved.conf
    if grep -q '^#\?DNSStubListener=' /etc/systemd/resolved.conf; then
      sed -i 's/^#\?DNSStubListener=.*/DNSStubListener=no/' /etc/systemd/resolved.conf || true
    else
      echo 'DNSStubListener=no' >> /etc/systemd/resolved.conf
    fi
    if ! grep -q '^DNS=' /etc/systemd/resolved.conf; then
      echo 'DNS=8.8.8.8' >> /etc/systemd/resolved.conf
    fi
    systemctl restart systemd-resolved || true
  fi

  for srv in bind9 named named-pkcs11 dnsmasq unbound pdns knot-resolver; do
    if systemctl is-active --quiet "$srv"; then
      log_info "Disabling conflicting service: $srv"
      systemctl stop "$srv" || true
      systemctl disable "$srv" >/dev/null 2>&1 || true
    fi
  done

  PIDS_ON_53="$(get_port53_pids)"
  if [[ -n "${PIDS_ON_53:-}" ]]; then
    while IFS= read -r pid; do
      [[ -z "$pid" ]] && continue
      cmdline="$(ps -p "$pid" -o cmd= 2>/dev/null || true)"
      if echo "$cmdline" | grep -qiE 'masterdnsvpn|masterdnsvpn_server'; then
        log_info "Stopping leftover MasterDnsVPN process on :53 (PID: $pid)"
        kill "$pid" 2>/dev/null || true
        sleep 1
        kill -9 "$pid" 2>/dev/null || true
      fi
    done <<< "$PIDS_ON_53"
  fi

  if check_port53; then
    OCC_INFO="$(ss -H -lupn 'sport = :53' 2>/dev/null | head -n1 | awk '{print $NF}' || true)"
    log_error "Port 53 is still occupied: ${OCC_INFO:-unknown}. Stop it manually and retry."
  fi
fi
log_success "Port 53 is available."

log_header "Configuring Firewall (Port 53 UDP/TCP)"
if command -v ufw >/dev/null 2>&1 && ufw status | grep -qw active; then
  ufw allow 53/udp >/dev/null 2>&1 || true
  ufw allow 53/tcp >/dev/null 2>&1 || true
  log_success "Port 53 (UDP/TCP) opened via UFW."
elif command -v firewall-cmd >/dev/null 2>&1 && systemctl is-active --quiet firewalld; then
  firewall-cmd --permanent --add-port=53/udp >/dev/null 2>&1 || true
  firewall-cmd --permanent --add-port=53/tcp >/dev/null 2>&1 || true
  firewall-cmd --reload >/dev/null 2>&1 || true
  log_success "Port 53 (UDP/TCP) opened via firewalld."
elif command -v iptables >/dev/null 2>&1; then
  iptables -C INPUT -p udp --dport 53 -j ACCEPT 2>/dev/null || iptables -I INPUT -p udp --dport 53 -j ACCEPT
  iptables -C INPUT -p tcp --dport 53 -j ACCEPT 2>/dev/null || iptables -I INPUT -p tcp --dport 53 -j ACCEPT
  if command -v ip6tables >/dev/null 2>&1; then
    ip6tables -C INPUT -p udp --dport 53 -j ACCEPT 2>/dev/null || ip6tables -I INPUT -p udp --dport 53 -j ACCEPT
    ip6tables -C INPUT -p tcp --dport 53 -j ACCEPT 2>/dev/null || ip6tables -I INPUT -p tcp --dport 53 -j ACCEPT
  fi
  if command -v netfilter-persistent >/dev/null 2>&1; then
    netfilter-persistent save >/dev/null 2>&1 || true
  elif command -v iptables-save >/dev/null 2>&1 && [[ -d /etc/iptables ]]; then
    iptables-save > /etc/iptables/rules.v4
    command -v ip6tables-save >/dev/null 2>&1 && ip6tables-save > /etc/iptables/rules.v6
  fi
  log_success "Port 53 (UDP/TCP) rule is ready via iptables."
else
  log_warn "No supported firewall tool detected. Skipping firewall setup."
fi

log_header "Tuning Kernel & Limits"
cat > /etc/sysctl.d/99-masterdnsvpn.conf <<'EOF'
# MasterDnsVPN high-load tuning
fs.file-max = 2097152
fs.nr_open = 2097152
net.core.somaxconn = 65535
net.core.netdev_max_backlog = 16384
net.core.optmem_max = 25165824
net.core.rmem_default = 262144
net.core.wmem_default = 262144
net.core.rmem_max = 33554432
net.core.wmem_max = 33554432
net.ipv4.udp_rmem_min = 16384
net.ipv4.udp_wmem_min = 16384
net.ipv4.udp_mem = 65536 131072 262144
net.ipv4.ip_local_port_range = 10240 65535
EOF
sysctl --system >/dev/null 2>&1 || log_warn "Could not fully apply sysctl settings."

cat > /etc/security/limits.d/99-masterdnsvpn.conf <<'EOF'
* soft nofile 1048576
* hard nofile 1048576
root soft nofile 1048576
root hard nofile 1048576
EOF
log_success "Kernel and file descriptor limits configured."

log_header "Fetching Latest Release"
ARCH="$(uname -m)"
if [[ "$ARCH" == "aarch64" || "$ARCH" == "arm64" ]]; then
  URL="https://github.com/masterking32/MasterDnsVPN/releases/latest/download/MasterDnsVPN_Server_Linux_ARM64.zip"
  PREFIX="MasterDnsVPN_Server_Linux_ARM64"
elif [[ "$ARCH" == "x86_64" ]]; then
  LEGACY=0
  [[ "${ID:-}" == "ubuntu" && ${VERSION_ID%%.*} -le 20 ]] && LEGACY=1
  [[ "${ID:-}" == "debian" && ${VERSION_ID%%.*} -le 11 ]] && LEGACY=1
  if [[ $LEGACY -eq 1 ]]; then
    log_info "Legacy system detected (GLIBC compatibility mode)."
    URL="https://github.com/masterking32/MasterDnsVPN/releases/latest/download/MasterDnsVPN_Server_Linux-Legacy_AMD64.zip"
    PREFIX="MasterDnsVPN_Server_Linux-Legacy_AMD64"
  else
    URL="https://github.com/masterking32/MasterDnsVPN/releases/latest/download/MasterDnsVPN_Server_Linux_AMD64.zip"
    PREFIX="MasterDnsVPN_Server_Linux_AMD64"
  fi
else
  log_error "Unsupported architecture: $ARCH"
fi

if [[ -f "server_config.toml" ]]; then
  mv -f server_config.toml server_config.toml.backup
  log_info "Existing config backed up."
fi

log_info "Downloading server binaries..."
if ! DOWNLOAD_DIR="$(mktemp -d /tmp/masterdnsvpn_download.XXXXXX 2>/dev/null)"; then
  DOWNLOAD_DIR="$(mktemp -d "$INSTALL_DIR/masterdnsvpn_download.XXXXXX" 2>/dev/null || true)"
fi
[[ -n "${DOWNLOAD_DIR:-}" && -d "${DOWNLOAD_DIR:-}" ]] || log_error "Failed to create temporary download directory. Check free space and /tmp permissions."
ZIP_PATH="${DOWNLOAD_DIR}/server.zip"

if ! curl -fL --retry 3 --retry-delay 2 --connect-timeout 15 -o "$ZIP_PATH" "$URL"; then
  log_warn "curl download failed, trying wget..."
  wget -qO "$ZIP_PATH" "$URL" || {
    log_warn "Disk usage snapshot:"
    df -h "$INSTALL_DIR" /tmp 2>/dev/null || true
    log_error "Download failed."
  }
fi

[[ -s "$ZIP_PATH" ]] || log_error "Downloaded archive is missing or empty: $ZIP_PATH"
unzip -q -o "$ZIP_PATH" -d "$INSTALL_DIR" || log_error "Failed to extract archive."
log_success "Files extracted."

EXECUTABLE="$(ls -t ${PREFIX}_v* 2>/dev/null | head -n1 || true)"
[[ -z "$EXECUTABLE" ]] && log_error "Binary not found in package."
chmod +x "$EXECUTABLE"
shopt -s nullglob
for old_bin in ${PREFIX}_v*; do
  [[ "$old_bin" == "$EXECUTABLE" ]] && continue
  rm -f -- "$old_bin"
done
shopt -u nullglob

log_header "Configuration"
[[ -f "server_config.toml" ]] || log_error "server_config.toml not found after extraction."
CURRENT_VERSION="$(extract_config_version server_config.toml)"
if [[ -z "${CURRENT_VERSION:-}" ]]; then
  log_error "Downloaded server_config.toml is invalid (CONFIG_VERSION missing)."
fi
if [[ -f "server_config.toml.backup" ]]; then
  BACKUP_VERSION="$(extract_config_version server_config.toml.backup)"
  if [[ -z "${BACKUP_VERSION:-}" ]]; then
    log_error "Backup config is too old (CONFIG_VERSION missing). Merge manually."
  fi

  if [[ "$BACKUP_VERSION" == "$CURRENT_VERSION" ]]; then
    mv -f server_config.toml.backup server_config.toml
    log_info "Config restored from backup."
  elif version_lt "$BACKUP_VERSION" "$CURRENT_VERSION"; then
    OLD_CFG_NAME="server_config_$(date +%Y%m%d_%H%M%S).toml"
    mv -f server_config.toml.backup "$OLD_CFG_NAME"
    log_warn "Old config version detected (backup=$BACKUP_VERSION < new=$CURRENT_VERSION)."
    log_warn "Previous config renamed to: $OLD_CFG_NAME"
    log_info "Using fresh config template; please set DOMAIN and other required fields."
  else
    log_error "Backup config version is newer than package config (backup=$BACKUP_VERSION, new=$CURRENT_VERSION). Merge manually."
  fi
fi

if [[ -f "server_config.toml" ]] && grep -q '"v.domain.com"' server_config.toml; then
  echo -e "${YELLOW}${BOLD}Attention:${NC} Set your NS domain."
  read -r -p ">>> Enter your Domain (e.g. vpn.example.com): " USER_DOMAIN </dev/tty || true
  if [[ -n "${USER_DOMAIN:-}" ]]; then
    sed -i -E "s|^DOMAIN[[:space:]]*=.*$|DOMAIN = [\"${USER_DOMAIN}\"]|" server_config.toml
  fi
fi

log_header "Security Initialization"
log_info "Starting server once to generate encryption key..."
./"$EXECUTABLE" > "$TMP_LOG" 2>&1 &
APP_PID=$!
READY=false
for _ in {1..10}; do
  if grep -q "Active Encryption Key" "$TMP_LOG" 2>/dev/null; then
    READY=true
    break
  fi
  sleep 1
done
kill "$APP_PID" 2>/dev/null || true
wait "$APP_PID" 2>/dev/null || true

if [[ "$READY" != true ]]; then
  log_warn "Initialization log tail:"
  tail -n 20 "$TMP_LOG" || true
  log_error "Could not verify key generation. Ensure Port 53 is free."
fi

echo -e "${GREEN}${BOLD}------------------------------------------------------"
echo -e "  YOUR ENCRYPTION KEY: ${NC}${CYAN}$(cat encrypt_key.txt 2>/dev/null)${NC}"
echo -e "${GREEN}${BOLD}------------------------------------------------------${NC}"

log_header "Installing System Service"
SVC="/etc/systemd/system/masterdnsvpn.service"
cat > "$SVC" <<EOF
[Unit]
Description=MasterDnsVPN Server
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
Type=simple
WorkingDirectory=$INSTALL_DIR
ExecStart=$INSTALL_DIR/$EXECUTABLE
Restart=always
RestartSec=3
User=root

LimitNOFILE=1048576
LimitNPROC=65535
TasksMax=infinity
TimeoutStopSec=15
KillMode=control-group

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable masterdnsvpn >/dev/null 2>&1
systemctl restart masterdnsvpn

if ! systemctl is-active --quiet masterdnsvpn; then
  journalctl -u masterdnsvpn -n 50 --no-pager || true
  log_error "Service failed to start. See logs above."
fi

log_success "MasterDnsVPN service is running."

echo -e "\n${CYAN}======================================================${NC}"
echo -e " ${GREEN}${BOLD}       INSTALLATION COMPLETED SUCCESSFULLY!${NC}"
echo -e "${CYAN}======================================================${NC}"
echo -e "${BOLD}Commands:${NC}"
echo -e "  ${YELLOW}>${NC} Start:   systemctl start masterdnsvpn"
echo -e "  ${YELLOW}>${NC} Stop:    systemctl stop masterdnsvpn"
echo -e "  ${YELLOW}>${NC} Restart: systemctl restart masterdnsvpn"
echo -e "  ${YELLOW}>${NC} Logs:    journalctl -u masterdnsvpn -f"
echo -e "\n${BOLD}Files:${NC}"
echo -e "  ${YELLOW}>${NC} ${INSTALL_DIR}/server_config.toml"
echo -e "  ${YELLOW}>${NC} ${INSTALL_DIR}/encrypt_key.txt"
echo -e "${YELLOW}Final Note:${NC} If config changes, run: systemctl restart masterdnsvpn"

rm -f *.spec >/dev/null 2>&1 || true

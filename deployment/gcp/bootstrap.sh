#!/usr/bin/env bash
set -euo pipefail
[[ $(id -u) = 0 ]] || { echo 'Run with sudo'; exit 1; }
apt-get update -qq
apt-get install -y docker.io docker-compose-v2 python3 openssl
systemctl enable --now docker
install -d -m 700 /opt/ipets/deployment
tar -xzf "$1" -C /opt/ipets/deployment
cd /opt/ipets/deployment
python3 setup_local.py
touch images.env
chmod 600 .env images.env
# Bound container logs keep a long-running development VM from filling its disk.
install -d /etc/docker
if [[ ! -e /etc/docker/daemon.json ]]; then
  printf '%s\n' '{"log-driver":"local","log-opts":{"max-size":"10m","max-file":"3"}}' > /etc/docker/daemon.json
  systemctl restart docker
fi
cat > /etc/systemd/system/ipets-autostop.service <<'UNIT'
[Unit]
Description=Stop idle development spending after a four-hour test session
[Service]
Type=oneshot
ExecStart=/usr/bin/systemctl poweroff
UNIT
cat > /etc/systemd/system/ipets-autostop.timer <<'UNIT'
[Unit]
Description=Four-hour development VM spending guard
[Timer]
OnActiveSec=4h
Unit=ipets-autostop.service
[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemctl enable --now ipets-autostop.timer
bash gcp/deploy.sh stack ''

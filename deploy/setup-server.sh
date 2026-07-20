#!/usr/bin/env bash
# 새 Ubuntu 서버 1회 셋업 — docker 설치 + 방화벽 개방 + 스왑 + 백업 크론. (멱등 — 재실행 안전)
# 오라클 Ubuntu 이미지는 기본 iptables가 22 외 전부 REJECT — ufw 대신 iptables에 직접 개방한다.
set -euo pipefail

# 1) docker
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
  sudo usermod -aG docker "$USER"
fi

# 2) 방화벽 — 80/443만 추가 개방 (OCI Security List와 이중 방어. 5432는 열지 않는다)
sudo iptables -C INPUT -p tcp -m multiport --dports 80,443 -j ACCEPT 2>/dev/null \
  || sudo iptables -I INPUT -p tcp -m multiport --dports 80,443 -j ACCEPT
sudo apt-get update -y && sudo apt-get install -y iptables-persistent rclone
sudo netfilter-persistent save

# 3) 스왑 4GB — OOM 방어 (A1 12GB에 스왑 0B이면 메모리 스파이크 시 프로세스 강제 종료)
if ! swapon --show --noheadings | grep -q '^/swapfile'; then
  sudo fallocate -l 4G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
fi
grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null

# 4) 백업 크론 (서버 UTC 19:10 = KST 04:10, 스크립트 경로는 ~/deploy 기준)
mkdir -p "$HOME/backups"
( crontab -l 2>/dev/null | grep -v 'scripts/backup.sh' || true ;
  echo "10 19 * * * $HOME/deploy/scripts/backup.sh >> $HOME/backups/backup.log 2>&1" ) | crontab -

echo "셋업 완료 — 재로그인(docker 그룹) 후 deploy/.env 채우고 'docker compose up -d'"

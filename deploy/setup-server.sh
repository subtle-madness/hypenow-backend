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
sudo apt-get update -y && sudo apt-get install -y iptables-persistent rclone zstd age
# zstd·age는 backup.sh 의존(08-04 압축 전환·09-02 오프사이트 암호화 — README §6-2)
sudo netfilter-persistent save

# 3) 스왑 4GB — OOM 방어 (A1 12GB에 스왑 0B이면 메모리 스파이크 시 프로세스 강제 종료)
if ! swapon --show --noheadings | grep -q '^/swapfile'; then
  sudo fallocate -l 4G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
fi
grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null

# 4) 백업 크론 (서버 UTC 15:00 = KST 00:00, 스크립트 경로는 ~/deploy 기준)
#    시각 근거(08-20): 배치가 전혀 없는 02:40~16:00 UTC 공백 구간의 끝자락 + KST 자정(API 한산).
#    구 19:10은 야간 배치 열차(16~19시 크롤 → 19:25 스냅샷 refresh → 19:30 미러 → 20:00 분석)
#    한복판이라 덤프·업로드 iowait(60~79%)가 배치 부하와 중첩돼 외부 health 프로브가 실패했다
#    (08-20 04:41 KST api-unreachable 알람). 백업은 파이프라인 결합이 없는 유일한 잡이라 이것만
#    옮긴다 — refresh를 옮기면 미러와의 "직전" 결합이 깨져 분석 크론 전체가 연쇄 조정된다.
#    백업 소요 ~45분(덤프 23분 + bwlimit 10M 업로드 20분) — 16:00 크롤 시작 전 완료 여유 확인 후 채번.
mkdir -p "$HOME/backups"
( crontab -l 2>/dev/null | grep -v 'scripts/backup.sh' || true ;
  echo "0 15 * * * $HOME/deploy/scripts/backup.sh >> $HOME/backups/backup.log 2>&1" ) | crontab -

echo "셋업 완료 — 재로그인(docker 그룹) 후 deploy/.env 채우고 'docker compose up -d'"

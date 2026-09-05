#!/usr/bin/env bash
# 서버에서 실행(크론, 15분마다): Hiker·DataImpulse 잔여 잔액/트래픽을 조회해 node-exporter
# textfile 컬렉터가 읽는 .prom 파일로 쓴다 — 09-04 수집 회귀 감시 트랙(그라파나 "잔액" 패널용).
#
# 크론(서버, README §14-2-6에서 등록):
#   */15 * * * * /home/ubuntu/deploy/scripts/vendor-balance.sh >> /home/ubuntu/vendor-balance.log 2>&1
# 의존성: curl, python3(표준 라이브러리 json만 — 서버 ubuntu에 둘 다 기본 설치).
#
# 지표 이름·라벨은 docs/tracks/GG-수집-회귀-감지-grafana.md §지표가 정본 — 대시보드
# hypenow-ops-collection.json과 알람 rules.yaml(vendor-*)이 이 이름을 그대로 쓰므로 임의로 바꾸지 않는다:
#   hypenow_vendor_balance{vendor="hiker",unit="requests"|"usd"}
#   hypenow_vendor_balance{vendor="dataimpulse_residential"|"dataimpulse_mobile",unit="bytes"}
#   hypenow_vendor_traffic_total_bytes{vendor=...} / hypenow_vendor_traffic_used_bytes{vendor=...}
#   hypenow_vendor_balance_scrape_ok{vendor=...} 1|0
#   hypenow_vendor_balance_updated_seconds (벤더 라벨 없음 — 스크립트 실행 시각 unix ts)
#
# 벤더 하나가 실패해도(네트워크·인증·파싱) 나머지는 정상 값을 쓰고 해당 벤더만
# scrape_ok=0으로 남긴다 — 벤더 API가 다 같이 죽어도 파일 자체는 항상 새로 써져서
# updated_seconds가 갱신되고(=크론이 살아 있다는 증거), Grafana 패널은 scrape_ok로 개별 벤더
# 장애를 구분할 수 있다. 시크릿(API 키·프록시 URL·파싱한 login:pass)은 어떤 경로로도
# stdout/stderr에 찍지 않는다 — 실패 로그는 벤더 이름과 실패 종류만 남긴다.
set -euo pipefail

ENV_FILE="${VENDOR_ENV_FILE:-$HOME/deploy/.env}"
OUT_DIR="${VENDOR_TEXTFILE_DIR:-$HOME/deploy/textfile}"
OUT_FILE="$OUT_DIR/vendor_balance.prom"

if [ ! -f "$ENV_FILE" ]; then
  echo "오류: env 파일 없음: $ENV_FILE" >&2
  exit 1
fi
# shellcheck disable=SC1090
set -a; source "$ENV_FILE"; set +a

mkdir -p "$OUT_DIR"

BALANCE_LINES=()
TOTAL_LINES=()
USED_LINES=()
OK_LINES=()

# Hiker: GET /sys/balance, 헤더 x-access-key. 응답 {"requests":..,"rate":..,"currency":"USD","amount":..}
fetch_hiker() {
  local vendor="hiker" resp parsed requests amount
  if [ -z "${HIKER_API_KEY:-}" ]; then
    echo "경고: HIKER_API_KEY 미설정 — $vendor 스킵" >&2
    OK_LINES+=("hypenow_vendor_balance_scrape_ok{vendor=\"$vendor\"} 0")
    return
  fi
  if ! resp="$(curl -sS --max-time 10 -H "x-access-key: $HIKER_API_KEY" \
      "https://api.hikerapi.com/sys/balance" 2>/dev/null)"; then
    echo "경고: $vendor 잔액 조회 실패(네트워크)" >&2
    OK_LINES+=("hypenow_vendor_balance_scrape_ok{vendor=\"$vendor\"} 0")
    return
  fi
  # 실패해도 스크립트를 죽이지 않게 `|| true`로 감싼다(set -e 하에서 커맨드 치환 실패가
  # 곧바로 대입문 실패로 이어지는 걸 막는 관용구 — backup.sh의 `remotes="$(... || true)"`와 동일).
  parsed="$(python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
    print(d.get("requests", ""), d.get("amount", ""))
except Exception:
    print("", "")
' <<< "$resp" 2>/dev/null || true)"
  read -r requests amount <<< "$parsed"
  if [ -z "$requests" ] || [ -z "$amount" ]; then
    echo "경고: $vendor 응답 파싱 실패" >&2
    OK_LINES+=("hypenow_vendor_balance_scrape_ok{vendor=\"$vendor\"} 0")
    return
  fi
  BALANCE_LINES+=("hypenow_vendor_balance{vendor=\"$vendor\",unit=\"requests\"} $requests")
  BALANCE_LINES+=("hypenow_vendor_balance{vendor=\"$vendor\",unit=\"usd\"} $amount")
  OK_LINES+=("hypenow_vendor_balance_scrape_ok{vendor=\"$vendor\"} 1")
}

# DataImpulse: GET /api/stats, basic auth = 프록시 URL(http://login:pass@host:port)의 login:pass.
# 응답 {"total_traffic":..,"traffic_used":..,"traffic_left":..,"status":"ok",...}
fetch_dataimpulse() {
  local vendor="$1" url_var="$2" proxy_url user pass resp parsed total used left status
  proxy_url="${!url_var:-}"
  if [ -z "$proxy_url" ]; then
    echo "경고: $url_var 미설정 — $vendor 스킵" >&2
    OK_LINES+=("hypenow_vendor_balance_scrape_ok{vendor=\"$vendor\"} 0")
    return
  fi
  if [[ "$proxy_url" =~ ^https?://([^:@]+):([^@]+)@ ]]; then
    user="${BASH_REMATCH[1]}"
    pass="${BASH_REMATCH[2]}"
  else
    echo "경고: $vendor 프록시 URL 형식 파싱 실패" >&2
    OK_LINES+=("hypenow_vendor_balance_scrape_ok{vendor=\"$vendor\"} 0")
    return
  fi
  if ! resp="$(curl -sS --max-time 10 -u "$user:$pass" \
      "https://gw.dataimpulse.com:777/api/stats" 2>/dev/null)"; then
    echo "경고: $vendor 잔액 조회 실패(네트워크)" >&2
    OK_LINES+=("hypenow_vendor_balance_scrape_ok{vendor=\"$vendor\"} 0")
    return
  fi
  parsed="$(python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
    print(d.get("total_traffic", ""), d.get("traffic_used", ""), d.get("traffic_left", ""), d.get("status", ""))
except Exception:
    print("", "", "", "")
' <<< "$resp" 2>/dev/null || true)"
  read -r total used left status <<< "$parsed"
  if [ -z "$left" ] || [ "$status" != "ok" ]; then
    echo "경고: $vendor 응답 파싱 실패 또는 status != ok" >&2
    OK_LINES+=("hypenow_vendor_balance_scrape_ok{vendor=\"$vendor\"} 0")
    return
  fi
  BALANCE_LINES+=("hypenow_vendor_balance{vendor=\"$vendor\",unit=\"bytes\"} $left")
  TOTAL_LINES+=("hypenow_vendor_traffic_total_bytes{vendor=\"$vendor\"} $total")
  USED_LINES+=("hypenow_vendor_traffic_used_bytes{vendor=\"$vendor\"} $used")
  OK_LINES+=("hypenow_vendor_balance_scrape_ok{vendor=\"$vendor\"} 1")
}

fetch_hiker
fetch_dataimpulse "dataimpulse_residential" DATAIMPULSE_RESIDENTIAL_PROXY_URL
fetch_dataimpulse "dataimpulse_mobile" DATAIMPULSE_MOBILE_PROXY_URL

# 원자적 쓰기 — node-exporter가 textfile 디렉토리를 주기적으로 스캔하는데, 쓰다 만 파일을
# 읽으면 파싱 에러로 스크레이프 전체가 깨진다(node_textfile_scrape_error). 같은 파일시스템
# 안의 tmp에 완성본을 쓰고 mv로 교체(POSIX rename은 원자적).
TMP_FILE="$(mktemp "$OUT_DIR/.vendor_balance.prom.XXXXXX")"
trap 'rm -f "$TMP_FILE"' EXIT

{
  echo "# HELP hypenow_vendor_balance 벤더 잔여 잔액 — unit=requests/usd(Hiker) 또는 bytes(DataImpulse 잔여 트래픽)"
  echo "# TYPE hypenow_vendor_balance gauge"
  for l in "${BALANCE_LINES[@]:-}"; do [ -n "$l" ] && echo "$l"; done

  echo "# HELP hypenow_vendor_traffic_total_bytes DataImpulse 플랜 총 트래픽 용량(바이트, 충전 시에만 증가)"
  echo "# TYPE hypenow_vendor_traffic_total_bytes gauge"
  for l in "${TOTAL_LINES[@]:-}"; do [ -n "$l" ] && echo "$l"; done

  echo "# HELP hypenow_vendor_traffic_used_bytes DataImpulse 플랜 누적 사용 트래픽(바이트, 단조 증가)"
  echo "# TYPE hypenow_vendor_traffic_used_bytes gauge"
  for l in "${USED_LINES[@]:-}"; do [ -n "$l" ] && echo "$l"; done

  echo "# HELP hypenow_vendor_balance_scrape_ok 이 벤더의 이번 실행 스크레이프 성공 여부(1=성공, 0=실패)"
  echo "# TYPE hypenow_vendor_balance_scrape_ok gauge"
  for l in "${OK_LINES[@]:-}"; do [ -n "$l" ] && echo "$l"; done

  echo "# HELP hypenow_vendor_balance_updated_seconds 이 스크립트가 마지막으로 실행 완료된 시각(unix ts)"
  echo "# TYPE hypenow_vendor_balance_updated_seconds gauge"
  echo "hypenow_vendor_balance_updated_seconds $(date +%s)"
} > "$TMP_FILE"

mv "$TMP_FILE" "$OUT_FILE"
trap - EXIT
echo "완료: $OUT_FILE"

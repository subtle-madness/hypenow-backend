#!/usr/bin/env bash
# 클로즈베타 배치 가입 코드 생성 — INSERT SQL을 표준 출력으로 낸다(실행은 파이프로).
#   사용법: ./generate-signup-codes.sh <채널> <수량>
#   예시(서버):  ./generate-signup-codes.sh THREADS 100 | docker compose exec -T postgres psql -U "$DB_USER" -d analysis
#   예시(로컬):  ./generate-signup-codes.sh DM 10 | docker exec -i "${PG_CONTAINER:-crawler-postgres-1}" psql -U crawler -d analysis
# 코드 형식: <채널>-XXXX (혼동 문자 0/O/1/I 제외 32자 알파벳). 기존 코드와 충돌은
# ON CONFLICT DO NOTHING으로 건너뛰므로, 실행 후 출력된 INSERT 건수를 수량과 대조할 것.
set -euo pipefail

CHANNEL="${1:?사용법: $0 <채널(예: THREADS)> <수량>}"
COUNT="${2:?사용법: $0 <채널(예: THREADS)> <수량>}"

if ! [[ "$CHANNEL" =~ ^[A-Z][A-Z0-9]*$ ]]; then
  echo "오류: 채널은 대문자 영숫자(예: THREADS, DM, LANDING)만 허용" >&2
  exit 1
fi

ALPHABET="ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

random_suffix() {
  local suffix=""
  for _ in 1 2 3 4; do
    suffix+="${ALPHABET:$((RANDOM % ${#ALPHABET})):1}"
  done
  printf '%s' "$suffix"
}

# 배치 내 중복 제거 — 같은 INSERT에 동일 코드가 두 번 오면 ON CONFLICT가 에러를 낸다
# (연관배열은 macOS 기본 bash 3.2에 없어 개행 구분 문자열로 대조)
seen=$'\n'
echo "INSERT INTO app.signup_codes (code, channel) VALUES"
for ((i = 1; i <= COUNT; i++)); do
  while true; do
    code="${CHANNEL}-$(random_suffix)"
    case "$seen" in *$'\n'"$code"$'\n'*) continue ;; *) break ;; esac
  done
  seen="${seen}${code}"$'\n'
  sep=$([ "$i" -lt "$COUNT" ] && echo "," || echo "")
  echo "  ('${code}', '${CHANNEL}')${sep}"
done
echo "ON CONFLICT (code) DO NOTHING;"

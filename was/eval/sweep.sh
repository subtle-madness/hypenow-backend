#!/usr/bin/env bash
# 브랜드 AI 챗 대량 스윕 러너 (2026-09-02 품질 세션).
#
# sweep-questions.json의 마케터 스타일 질문(체인은 conversationId 유지)을 로컬 was에 순차로
# 쏘고, 결과(답변·tool_calls·limitReached)를 JSONL로 남긴다. 채점하지 않는다 - 스크리닝은
# 별도(sweep-screen.sh). 로그인·CSRF·한도 상향/복원 관용구는 run.sh를 따른다.
#
# 사용법:
#   ./sweep.sh                 # 전체 실행 (실 Vertex 호출 - 비용 발생)
#   OUT=... QUESTIONS=... ./sweep.sh
#
# 재실행 시 OUT에 이미 완주한 항목(id의 turns 전부 기록됨)은 건너뛴다 - 중단 후 이어달리기용.
set -euo pipefail
cd "$(dirname "$0")"

WAS_BASE="${WAS_BASE:-http://localhost:8081}"
BRAND_ID="${BRAND_ID:-128}"
EVAL_EMAIL="${EVAL_EMAIL:-poc@test.local}"
EVAL_PASSWORD="${EVAL_PASSWORD:-poc-test-1234}"
PG_CONTAINER="${PG_CONTAINER:-crawler-postgres-1}"
PG_USER="${PG_USER:-crawler}"
APP_DB="${APP_DB:-analysis}"

QUESTIONS="${QUESTIONS:-sweep-questions.json}"
OUT="${OUT:-sweep-results.jsonl}"

DAILY_LIMIT_KEY="ai.chat.daily-limit"
PER_MIN_KEY="ai.chat.per-minute-limit"
# 스윕 종료 후 복원값 - 로컬 기준값(300/10). run.sh의 30과 다른 것은 의도(09-02 세션 지시).
RESTORE_DAILY_LIMIT="${RESTORE_DAILY_LIMIT:-300}"
RESTORE_PER_MIN_LIMIT="${RESTORE_PER_MIN_LIMIT:-10}"
TEMP_LIMIT=999

app_psql() {
	docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$APP_DB" -v ON_ERROR_STOP=1 -q "$@"
}

xsrf_token() {
	awk -F'\t' '$6=="XSRF-TOKEN"{v=$7} END{print v}' "$COOKIE_JAR"
}

restore_settings() {
	echo "app_setting 한도 복원 중 (daily=${RESTORE_DAILY_LIMIT}, per-minute=${RESTORE_PER_MIN_LIMIT})..."
	app_psql -c "UPDATE app.app_setting SET value='${RESTORE_DAILY_LIMIT}' WHERE key='${DAILY_LIMIT_KEY}';" >/dev/null 2>&1 || true
	app_psql -c "UPDATE app.app_setting SET value='${RESTORE_PER_MIN_LIMIT}' WHERE key='${PER_MIN_KEY}';" >/dev/null 2>&1 || true
}

cleanup() {
	restore_settings
	[[ -n "${COOKIE_JAR:-}" && -f "${COOKIE_JAR:-}" ]] && rm -f "$COOKIE_JAR"
}

# ---------- 전제 확인 ----------
for cmd in curl jq docker; do
	command -v "$cmd" >/dev/null 2>&1 || {
		echo "오류: $cmd 필요"
		exit 1
	}
done
docker exec "$PG_CONTAINER" true >/dev/null 2>&1 || {
	echo "오류: 컨테이너 ${PG_CONTAINER} 접근 불가 (DOCKER_HOST 확인)"
	exit 1
}
curl -sS -o /dev/null "$WAS_BASE/api/me" || {
	echo "오류: was(${WAS_BASE}) 응답 없음"
	exit 1
}
jq empty "$QUESTIONS" || {
	echo "오류: ${QUESTIONS} 파싱 실패"
	exit 1
}

trap cleanup EXIT
echo "한도 임시 상향 (daily/per-minute -> ${TEMP_LIMIT})..."
app_psql -c "UPDATE app.app_setting SET value='${TEMP_LIMIT}' WHERE key='${DAILY_LIMIT_KEY}';"
app_psql -c "UPDATE app.app_setting SET value='${TEMP_LIMIT}' WHERE key='${PER_MIN_KEY}';"

# ---------- 로그인 ----------
COOKIE_JAR=$(mktemp)
curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" -o /dev/null "$WAS_BASE/api/me" || true
login_code=$(curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
	-H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(xsrf_token)" \
	-o /dev/null -w '%{http_code}' \
	-X POST "$WAS_BASE/v1/auth/login" \
	-d "$(jq -n --arg email "$EVAL_EMAIL" --arg password "$EVAL_PASSWORD" '{email: $email, password: $password}')")
if [[ "$login_code" != "200" ]]; then
	echo "오류: 로그인 실패(HTTP ${login_code})"
	exit 1
fi
echo "로그인 완료 - brandId=${BRAND_ID}, 결과 파일=${OUT}"

touch "$OUT"
total_items=$(jq length "$QUESTIONS")
item_idx=0

while IFS= read -r -u 3 item_json; do
	item_idx=$((item_idx + 1))
	id=$(jq -r '.id' <<<"$item_json")
	n_turns=$(jq -r '.turns | length' <<<"$item_json")
	done_turns=$(jq -c --arg id "$id" 'select(.id == $id)' "$OUT" 2>/dev/null | wc -l | tr -d ' ')
	if [[ "$done_turns" -ge "$n_turns" ]]; then
		echo "[$item_idx/$total_items] $id SKIP(이미 완료)"
		continue
	fi
	# 부분 완료 항목은 체인 conversationId를 복원할 수 없으니 통째로 다시 - 기존 행 제거.
	if [[ "$done_turns" -gt 0 ]]; then
		tmp=$(mktemp)
		jq -c --arg id "$id" 'select(.id != $id)' "$OUT" >"$tmp" && mv "$tmp" "$OUT"
	fi

	conv_id=""
	turn_idx=0
	while IFS= read -r -u 4 question; do
		turn_idx=$((turn_idx + 1))
		if [[ -n "$conv_id" ]]; then
			body=$(jq -n --arg text "$question" --arg brand "$BRAND_ID" --arg conv "$conv_id" \
				'{accountIds: [$brand], text: $text, conversationId: $conv}')
		else
			body=$(jq -n --arg text "$question" --arg brand "$BRAND_ID" \
				'{accountIds: [$brand], text: $text}')
		fi

		tmp_body=$(mktemp)
		http_code=$(curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
			-H "Content-Type: application/json" -H "Accept: application/json" \
			-H "X-XSRF-TOKEN: $(xsrf_token)" \
			-o "$tmp_body" -w '%{http_code}' --max-time 180 \
			-X POST "$WAS_BASE/v1/brand-monitoring/ai/messages" -d "$body") || http_code="000"
		json_body=$(cat "$tmp_body")
		rm -f "$tmp_body"

		answer=""
		message_id=""
		limit_reached=""
		tool_calls="[]"
		if [[ "$http_code" == "200" ]] && jq -e '.success == true' >/dev/null 2>&1 <<<"$json_body"; then
			answer=$(jq -r '.data.content // ""' <<<"$json_body")
			message_id=$(jq -r '.data.messageId // empty' <<<"$json_body")
			limit_reached=$(jq -r '.data.limitReached // empty' <<<"$json_body")
			conv_id=$(jq -r '.data.conversationId // empty' <<<"$json_body")
			if [[ -n "$message_id" ]]; then
				tool_calls=$(app_psql -t -A -c \
					"SELECT COALESCE(tool_calls::text, '[]') FROM app.ai_chat_logs WHERE id = ${message_id};" 2>/dev/null | tr -d '\n')
				[[ -z "$tool_calls" ]] && tool_calls="[]"
			fi
		else
			# 실패 응답은 원문을 answer 자리에 남겨 스크리닝에서 부류로 잡는다.
			answer="$json_body"
		fi

		jq -nc \
			--arg id "$id" --argjson turn "$turn_idx" --arg category "$(jq -r '.category // ""' <<<"$item_json")" \
			--arg question "$question" --arg http "$http_code" \
			--arg conv "$conv_id" --arg msg "$message_id" --arg limitReached "$limit_reached" \
			--arg answer "$answer" --argjson toolCalls "$tool_calls" \
			'{id: $id, turn: $turn, category: $category, question: $question, http: $http,
			  conversationId: $conv, messageId: $msg, limitReached: $limitReached,
			  answer: $answer, toolCalls: $toolCalls}' >>"$OUT"

		echo "[$item_idx/$total_items] $id turn${turn_idx}/${n_turns} http=${http_code} tools=$(jq 'length' <<<"$tool_calls") len=${#answer}"
		sleep 1
	done 4< <(jq -r '.turns[]' <<<"$item_json")
done 3< <(jq -c '.[]' "$QUESTIONS")

echo
echo "스윕 완료: $(wc -l <"$OUT" | tr -d ' ')행 -> ${OUT}"

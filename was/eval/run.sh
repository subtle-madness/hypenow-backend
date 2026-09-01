#!/usr/bin/env bash
# 브랜드 AI 챗 eval 러너 (설계 §7-2, 계획 2026-09-01 Task 4).
#
# was/eval/goldset.json의 케이스를 로컬 was(POST /v1/brand-monitoring/ai/messages, 완결 JSON
# 경로)에 실제로 쏘고, app.ai_chat_logs에 남은 tool_calls·answer를 psql로 읽어 결정론으로
# 채점한다(analytics/test/run.sh 하니스 컨벤션 참고 - PG_CONTAINER 오버라이드·PASS/표 출력 스타일).
#
# 사용법:
#   ./run.sh                # 전체 케이스 실행(실 Vertex 호출 - 비용 발생, 로컬 was·DB 필요)
#   ./run.sh --self-test    # 채점 로직만 mock 데이터로 검증(네트워크·DB 접근 없음)
#
# 실 Vertex 비용 때문에 Task 4(구현 세션)는 --self-test까지만 확인한다. 전체 실행은 Task 5
# (메인 세션)나 이후 회귀 검증에서 사람이 직접 돌린다. README.md 참고.
set -euo pipefail
shopt -s nullglob
cd "$(dirname "$0")"

# ---------- 설정(env 오버라이드) ----------
WAS_BASE="${WAS_BASE:-http://localhost:8081}"
BRAND_ID="${BRAND_ID:-128}"
EVAL_EMAIL="${EVAL_EMAIL:-poc@test.local}"
EVAL_PASSWORD="${EVAL_PASSWORD:-poc-test-1234}"
# 컨테이너 이름은 compose 디렉토리명 기반이라 머신마다 다르다 — PG_CONTAINER로 오버라이드.
PG_CONTAINER="${PG_CONTAINER:-crawler-postgres-1}"
PG_USER="${PG_USER:-crawler}"
# app 스키마(app.ai_chat_logs·app.app_setting)는 was의 application.yml 기준 analysis DB에 있다.
# groundTruthSql은 monitoring DB(brand_tagged_post·brand_post_meta)를 별도로 쓴다 — 같은 컨테이너,
# 다른 DB (CLAUDE.md 컨벤션: PG_CONTAINER는 컨테이너, DB 이름은 -d로 구분).
APP_DB="${APP_DB:-analysis}"
MONITORING_DB="${MONITORING_DB:-monitoring}"

GOLDSET="goldset.json"
DAILY_LIMIT_KEY="ai.chat.daily-limit"
PER_MIN_KEY="ai.chat.per-minute-limit"
BASE_DAILY_LIMIT=30
BASE_PER_MIN_LIMIT=10
TEMP_LIMIT=999

# ---------- psql 헬퍼 ----------
app_psql() {
	docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$APP_DB" -v ON_ERROR_STOP=1 -q "$@"
}

monitoring_psql() {
	docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$MONITORING_DB" -v ON_ERROR_STOP=1 -q "$@"
}

# ---------- 채점 로직(순수 함수 - HTTP·DB 접근 없음, --self-test가 이 블록만 검증) ----------

# 콤마 천단위 구분 포맷팅(로케일 의존 printf %'d 대신 순수 bash로 구현 - macOS/리눅스 모두 동작).
add_commas() {
	local n="$1" sign="" result="" cnt=0 i
	if [[ "$n" == -* ]]; then
		sign="-"
		n="${n#-}"
	fi
	for ((i = ${#n} - 1; i >= 0; i--)); do
		result="${n:$i:1}$result"
		cnt=$((cnt + 1))
		if ((cnt % 3 == 0 && i != 0)); then
			result=",$result"
		fi
	done
	printf '%s%s' "$sign" "$result"
}

# 툴 호출 1건이 rule(name + 선택적 argsInclude/argsHasKeys)에 매치하는 호출을 tool_calls_json
# 안에서 하나라도 찾으면 exit 0. argsInclude는 부분매치(rule에 있는 키만 값 일치를 요구),
# argsHasKeys는 값과 무관하게 키 존재만 확인(예: get_comments의 shortCodes 배열 사용 여부).
tools_match_rule() {
	local calls_json="$1" rule_json="$2"
	jq -n -e --argjson calls "$calls_json" --argjson rule "$rule_json" '
		def argsMatch($a; $r):
			(($r.argsInclude // {}) | to_entries | all(($a[.key] // null) == .value))
			and (($r.argsHasKeys // []) | all(. as $k | $a | has($k)));
		[$calls[] | select(.name == $rule.name and argsMatch(.args // {}; $rule))] | length > 0
	' >/dev/null 2>&1
}

# expectTools: 목록의 모든 rule이 각각 하나 이상의 호출과 매치해야 통과.
check_expect_tools() {
	local calls_json="$1" rules_json="$2" rule
	while IFS= read -r rule; do
		[[ -z "$rule" ]] && continue
		if ! tools_match_rule "$calls_json" "$rule"; then
			return 1
		fi
	done < <(printf '%s' "$rules_json" | jq -c '.[]')
	return 0
}

# forbidTools: 목록의 rule 중 하나라도 매치하는 호출이 있으면 실패.
check_forbid_tools() {
	local calls_json="$1" rules_json="$2" rule
	while IFS= read -r rule; do
		[[ -z "$rule" ]] && continue
		if tools_match_rule "$calls_json" "$rule"; then
			return 1
		fi
	done < <(printf '%s' "$rules_json" | jq -c '.[]')
	return 0
}

# expectAnswerContains: 목록의 모든 문자열이 답변 텍스트에 부분 문자열로 있어야 통과.
check_answer_contains() {
	local answer="$1" needles_json="$2" needle
	while IFS= read -r needle; do
		[[ -z "$needle" ]] && continue
		case "$answer" in
		*"$needle"*) ;;
		*) return 1 ;;
		esac
	done < <(printf '%s' "$needles_json" | jq -r '.[]')
	return 0
}

# groundTruthSql 실행값이 콤마 포맷/무콤마 포맷 둘 중 하나로 답변에 등장하면 통과.
check_ground_truth() {
	local answer="$1" value="$2" commafmt
	commafmt="$(add_commas "$value")"
	case "$answer" in
	*"$value"*) return 0 ;;
	*"$commafmt"*) return 0 ;;
	*) return 1 ;;
	esac
}

# ---------- --self-test ----------
SELFTEST_FAILURES=0

assert_true() {
	local desc="$1"
	shift
	if "$@" >/dev/null 2>&1; then
		echo "  OK   $desc"
	else
		echo "  FAIL $desc"
		SELFTEST_FAILURES=$((SELFTEST_FAILURES + 1))
	fi
}

assert_false() {
	local desc="$1"
	shift
	if ! "$@" >/dev/null 2>&1; then
		echo "  OK   $desc"
	else
		echo "  FAIL $desc (실패해야 하는데 통과함)"
		SELFTEST_FAILURES=$((SELFTEST_FAILURES + 1))
	fi
}

assert_eq() {
	local desc="$1" expected="$2" actual="$3"
	if [[ "$expected" == "$actual" ]]; then
		echo "  OK   $desc"
	else
		echo "  FAIL $desc (expected=[$expected] actual=[$actual])"
		SELFTEST_FAILURES=$((SELFTEST_FAILURES + 1))
	fi
}

run_self_test() {
	echo "== add_commas =="
	assert_eq "0" "0" "$(add_commas 0)"
	assert_eq "273" "273" "$(add_commas 273)"
	assert_eq "1234 -> 1,234" "1,234" "$(add_commas 1234)"
	assert_eq "12345 -> 12,345" "12,345" "$(add_commas 12345)"
	assert_eq "-1234 -> -1,234" "-1,234" "$(add_commas -1234)"

	echo "== tools_match_rule: argsInclude 부분 매치 (ad-posts-top10 시나리오) =="
	local ad_ok='[{"name":"aggregate_posts","args":{"groupBy":"author","sponsorship":"sponsored"},"rows":10}]'
	local ad_bad='[{"name":"aggregate_posts","args":{"groupBy":"author","keyword":"광고"},"rows":10}]'
	assert_true "sponsorship=sponsored 호출은 expectTools 매치" \
		tools_match_rule "$ad_ok" '{"name":"aggregate_posts","argsInclude":{"groupBy":"author","sponsorship":"sponsored"}}'
	assert_false "keyword=광고 호출은 sponsorship 룰에 안 걸림" \
		tools_match_rule "$ad_bad" '{"name":"aggregate_posts","argsInclude":{"groupBy":"author","sponsorship":"sponsored"}}'
	assert_true "keyword=광고 호출은 forbidTools 룰에 걸림" \
		tools_match_rule "$ad_bad" '{"name":"aggregate_posts","argsInclude":{"keyword":"광고"}}'
	assert_false "다른 툴 이름(list_posts)에는 안 걸림" \
		tools_match_rule "$ad_ok" '{"name":"list_posts","argsInclude":{}}'

	echo "== tools_match_rule: argsHasKeys (comments-summary의 shortCodes 배열 확인) =="
	local calls_array='[{"name":"get_comments","args":{"shortCodes":["a","b","c"],"limit":10},"rows":15}]'
	local calls_repeated='[{"name":"get_comments","args":{"shortCode":"a"},"rows":5},{"name":"get_comments","args":{"shortCode":"b"},"rows":5}]'
	assert_true "shortCodes 배열 1회 호출은 매치" \
		tools_match_rule "$calls_array" '{"name":"get_comments","argsHasKeys":["shortCodes"]}'
	assert_false "shortCode 단건 반복 호출은 argsHasKeys(shortCodes) 매치 안 됨" \
		tools_match_rule "$calls_repeated" '{"name":"get_comments","argsHasKeys":["shortCodes"]}'

	echo "== check_expect_tools / check_forbid_tools(여러 rule 조합) =="
	assert_true "expectTools 전부 매치" \
		check_expect_tools "$ad_ok" '[{"name":"aggregate_posts","argsInclude":{"groupBy":"author","sponsorship":"sponsored"}}]'
	assert_false "expectTools 중 하나라도 불일치면 실패" \
		check_expect_tools "$ad_bad" '[{"name":"aggregate_posts","argsInclude":{"groupBy":"author","sponsorship":"sponsored"}}]'
	assert_true "forbidTools 위반 없음" \
		check_forbid_tools "$ad_ok" '[{"name":"aggregate_posts","argsInclude":{"keyword":"광고"}},{"name":"search_posts","argsInclude":{"query":"광고"}}]'
	assert_false "forbidTools 중 하나라도 매치되면 실패" \
		check_forbid_tools "$ad_bad" '[{"name":"aggregate_posts","argsInclude":{"keyword":"광고"}},{"name":"search_posts","argsInclude":{"query":"광고"}}]'

	echo "== check_answer_contains =="
	assert_true "필요한 문구가 있으면 통과" \
		check_answer_contains "표본이 1개뿐인 계정이 있어 해석에 주의하세요" '["표본"]'
	assert_false "필요한 문구가 없으면 실패" \
		check_answer_contains "정상적으로 집계했어요" '["표본"]'

	echo "== check_ground_truth(콤마 유무 두 포맷 허용) =="
	assert_true "콤마 포맷 매치" check_ground_truth "총 1,234건이에요" "1234"
	assert_true "무콤마 포맷 매치" check_ground_truth "총 1234건이에요" "1234"
	assert_true "3자리 미만은 콤마 없이도 매치" check_ground_truth "총 273건이에요" "273"
	assert_false "값이 다르면 실패" check_ground_truth "총 999건이에요" "1234"

	echo
	if [[ $SELFTEST_FAILURES -eq 0 ]]; then
		echo "SELF-TEST ALL GREEN"
		return 0
	fi
	echo "SELF-TEST FAILED: ${SELFTEST_FAILURES}건"
	return 1
}

if [[ "${1:-}" == "--self-test" ]]; then
	run_self_test
	exit $?
fi

# ---------- 이하 실 러너 본체(--self-test가 아닐 때만) ----------

print_row() {
	printf '%-28s %-6s %s\n' "$1" "$2" "$3"
}

# 쿠키 jar(Netscape 포맷)에서 XSRF-TOKEN 쿠키 값을 읽는다 - 매 쓰기 요청 직전에 새로 읽어야
# 한다(SpaCsrfTokenRequestHandler가 지연 발급이라 첫 요청 이후에만 쿠키가 실린다).
xsrf_token() {
	awk -F'\t' '$6=="XSRF-TOKEN"{v=$7} END{print v}' "$COOKIE_JAR"
}

restore_settings() {
	echo "app_setting 한도 복원 중 (daily=${BASE_DAILY_LIMIT}, per-minute=${BASE_PER_MIN_LIMIT})..."
	app_psql -c "UPDATE app.app_setting SET value='${BASE_DAILY_LIMIT}' WHERE key='${DAILY_LIMIT_KEY}';" >/dev/null 2>&1 || true
	app_psql -c "UPDATE app.app_setting SET value='${BASE_PER_MIN_LIMIT}' WHERE key='${PER_MIN_KEY}';" >/dev/null 2>&1 || true
}

cleanup() {
	restore_settings
	[[ -n "${COOKIE_JAR:-}" && -f "${COOKIE_JAR:-}" ]] && rm -f "$COOKIE_JAR"
}

# 케이스 1건 실행 - 실패해도 스크립트가 죽지 않도록 이 함수 안에서 전부 처리한다(에러는 SKIP/FAIL로 흡수).
process_case() {
	local case_json="$1"
	local id question preset_id expect_tools forbid_tools expect_answer ground_truth_sql
	id=$(jq -r '.id' <<<"$case_json")
	question=$(jq -r '.question' <<<"$case_json")
	preset_id=$(jq -r '.presetId // empty' <<<"$case_json")
	expect_tools=$(jq -c '.expectTools // []' <<<"$case_json")
	forbid_tools=$(jq -c '.forbidTools // []' <<<"$case_json")
	expect_answer=$(jq -c '.expectAnswerContains // []' <<<"$case_json")
	ground_truth_sql=$(jq -r '.groundTruthSql // empty' <<<"$case_json")

	local body
	if [[ -n "$preset_id" ]]; then
		body=$(jq -n --arg text "$question" --arg brand "$BRAND_ID" --arg preset "$preset_id" \
			'{accountIds: [$brand], text: $text, presetId: $preset}')
	else
		body=$(jq -n --arg text "$question" --arg brand "$BRAND_ID" \
			'{accountIds: [$brand], text: $text}')
	fi

	local tmp_body http_code
	tmp_body=$(mktemp)
	if ! http_code=$(curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
		-H "Content-Type: application/json" -H "Accept: application/json" \
		-H "X-XSRF-TOKEN: $(xsrf_token)" \
		-o "$tmp_body" -w '%{http_code}' \
		-X POST "$WAS_BASE/v1/brand-monitoring/ai/messages" -d "$body"); then
		http_code="000"
	fi
	local json_body
	json_body=$(cat "$tmp_body")
	rm -f "$tmp_body"

	if [[ "$http_code" != "200" ]]; then
		local err_detail
		err_detail=$(jq -r '(.error.code // "?") + ": " + (.error.message // "")' <<<"$json_body" 2>/dev/null)
		print_row "$id" "SKIP" "API 실패(HTTP $http_code) ${err_detail:-}"
		skip_count=$((skip_count + 1))
		return
	fi

	local success
	success=$(jq -r '.success // "false"' <<<"$json_body" 2>/dev/null || echo "false")
	if [[ "$success" != "true" ]]; then
		print_row "$id" "SKIP" "success=false: $(jq -c '.error // {}' <<<"$json_body" 2>/dev/null)"
		skip_count=$((skip_count + 1))
		return
	fi

	local answer message_id
	answer=$(jq -r '.data.content // ""' <<<"$json_body")
	message_id=$(jq -r '.data.messageId // empty' <<<"$json_body")

	local tool_calls_json
	if [[ -n "$message_id" ]]; then
		tool_calls_json=$(app_psql -t -A -c \
			"SELECT COALESCE(tool_calls::text, '[]') FROM app.ai_chat_logs WHERE id = ${message_id};" 2>/dev/null | tr -d '\n')
	else
		tool_calls_json=$(app_psql -t -A -c \
			"SELECT COALESCE(tool_calls::text, '[]') FROM app.ai_chat_logs WHERE user_id = ${EVAL_USER_ID} ORDER BY id DESC LIMIT 1;" 2>/dev/null | tr -d '\n')
	fi
	if [[ -z "$tool_calls_json" ]] || ! jq -e . >/dev/null 2>&1 <<<"$tool_calls_json"; then
		print_row "$id" "SKIP" "ai_chat_logs 조회 실패(psql) - messageId=${message_id:-없음}"
		skip_count=$((skip_count + 1))
		return
	fi

	local ok=1
	local -a details=()
	if ! check_expect_tools "$tool_calls_json" "$expect_tools"; then
		ok=0
		details+=("expectTools 불일치(기대 $expect_tools / 실제 $tool_calls_json)")
	fi
	if ! check_forbid_tools "$tool_calls_json" "$forbid_tools"; then
		ok=0
		details+=("forbidTools 위반(금지 $forbid_tools / 실제 $tool_calls_json)")
	fi
	if ! check_answer_contains "$answer" "$expect_answer"; then
		ok=0
		details+=("expectAnswerContains 불일치($expect_answer)")
	fi

	local gt_note=""
	if [[ -n "$ground_truth_sql" ]]; then
		local sql gt_value
		sql="${ground_truth_sql//:BRAND_ID/$BRAND_ID}"
		if gt_value=$(monitoring_psql -t -A -c "$sql" 2>/dev/null | tr -d '[:space:]'); then
			if [[ "$gt_value" =~ ^-?[0-9]+$ ]]; then
				if ! check_ground_truth "$answer" "$gt_value"; then
					ok=0
					details+=("groundTruth 불일치(기대값=${gt_value}가 답변에 없음)")
				fi
			else
				gt_note=" [수치검증 SKIP: 결과값 비정수 '${gt_value}']"
			fi
		else
			gt_note=" [수치검증 SKIP: groundTruthSql 실행 실패]"
		fi
	fi

	if [[ $ok -eq 1 ]]; then
		print_row "$id" "PASS" "${gt_note}"
		pass_count=$((pass_count + 1))
	else
		local joined
		joined=$(
			IFS='; '
			echo "${details[*]}"
		)
		print_row "$id" "FAIL" "${joined}${gt_note}"
		fail_count=$((fail_count + 1))
	fi
}

# ---------- 1. 전제 확인 ----------
command -v curl >/dev/null 2>&1 || {
	echo "오류: curl이 필요합니다."
	exit 1
}
command -v jq >/dev/null 2>&1 || {
	echo "오류: jq가 필요합니다."
	exit 1
}
command -v docker >/dev/null 2>&1 || {
	echo "오류: docker가 필요합니다."
	exit 1
}

if [[ -z "${DOCKER_HOST:-}" ]]; then
	echo "안내: 셸에 DOCKER_HOST가 설정돼 있지 않습니다. colima 사용 시 아래를 먼저 실행하세요:"
	echo "  export DOCKER_HOST=unix://\$HOME/.colima/default/docker.sock"
	echo "(docker 명령이 이미 정상 동작하면 무시해도 됩니다 - 접근성 확인 중...)"
fi

if ! docker exec "$PG_CONTAINER" true >/dev/null 2>&1; then
	echo "오류: docker exec으로 컨테이너 '${PG_CONTAINER}'에 접근할 수 없습니다."
	echo "  - colima가 떠 있는지(colima start), 컨테이너 이름이 맞는지(PG_CONTAINER로 오버라이드) 확인하세요."
	echo "  - export DOCKER_HOST=unix://\$HOME/.colima/default/docker.sock 을 실행해보세요."
	exit 1
fi

if ! curl -sS -o /dev/null "$WAS_BASE/api/me"; then
	echo "오류: was(${WAS_BASE})가 응답하지 않습니다. ./gradlew :was:bootRun 으로 기동했는지 확인하세요."
	exit 1
fi

if [[ ! -f "$GOLDSET" ]]; then
	echo "오류: ${GOLDSET}이 없습니다."
	exit 1
fi
jq empty "$GOLDSET" || {
	echo "오류: ${GOLDSET} JSON 파싱 실패"
	exit 1
}

# ---------- 2. app_setting 한도 임시 상향(trap으로 종료 시 복원) ----------
trap cleanup EXIT
echo "app_setting 한도 임시 상향 중 (daily/per-minute -> ${TEMP_LIMIT})..."
app_psql -c "UPDATE app.app_setting SET value='${TEMP_LIMIT}' WHERE key='${DAILY_LIMIT_KEY}';"
app_psql -c "UPDATE app.app_setting SET value='${TEMP_LIMIT}' WHERE key='${PER_MIN_KEY}';"

# ---------- 3. 로그인(CSRF 쿠키 확보 -> /api/auth/login) ----------
COOKIE_JAR=$(mktemp)
# CSRF 토큰은 지연 발급이다(SpaCsrfTokenRequestHandler) - 아무 요청이나 한 번 태워야 XSRF-TOKEN
# 쿠키가 내려온다. 401이 나도 무시한다(쿠키 발급이 목적이지 인증이 아니다).
curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" -o /dev/null "$WAS_BASE/api/me" || true

login_tmp=$(mktemp)
if ! login_code=$(curl -sS -c "$COOKIE_JAR" -b "$COOKIE_JAR" \
	-H "Content-Type: application/json" -H "X-XSRF-TOKEN: $(xsrf_token)" \
	-o "$login_tmp" -w '%{http_code}' \
	-X POST "$WAS_BASE/api/auth/login" \
	-d "$(jq -n --arg email "$EVAL_EMAIL" --arg password "$EVAL_PASSWORD" '{email: $email, password: $password}')"); then
	login_code="000"
fi
login_body=$(cat "$login_tmp")
rm -f "$login_tmp"

if [[ "$login_code" != "200" ]]; then
	echo "오류: 로그인 실패(HTTP ${login_code}) - EVAL_EMAIL/EVAL_PASSWORD(${EVAL_EMAIL})가 로컬 DB에 있는지 확인하세요."
	echo "$login_body"
	exit 1
fi
EVAL_USER_ID=$(jq -r '.id' <<<"$login_body")
echo "로그인 완료 - userId=${EVAL_USER_ID}, brandId=${BRAND_ID}"
echo "(그 브랜드를 이 계정이 보유하지 않으면 이후 모든 케이스가 404로 SKIP됩니다 - app.brand_monitorings 확인)"

# ---------- 4. 케이스별 실행·채점 ----------
pass_count=0
fail_count=0
skip_count=0

echo
print_row "CASE" "STATUS" "DETAIL"
print_row "----" "------" "------"

while IFS= read -r case_json; do
	process_case "$case_json"
	sleep 1
done < <(jq -c '.[]' "$GOLDSET")

echo
echo "PASS=${pass_count} FAIL=${fail_count} SKIP=${skip_count}"

if [[ $fail_count -gt 0 ]]; then
	exit 1
fi
exit 0

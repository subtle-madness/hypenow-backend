#!/usr/bin/env bash
# 스윕 결과 결정론 스크리닝 (2026-09-02 품질 세션).
#
# sweep.sh가 남긴 JSONL을 기대값 없이 규칙만으로 훑어 "실패 후보"를 표면화한다. 후보는 사람이
# 읽고 진짜 실패만 분류한다 - 이 스크립트는 채점기가 아니라 깔때기다.
#
# 규칙:
#   A refusal-in-domain : impossible 부류가 아닌데 거절/불가 문구가 답변에 있음
#   B fabrication       : 툴 0회인데 수치·@계정명이 있는 답변(체인 2턴+는 이전 턴 결과 재인용일
#                         수 있어 별도 표기)
#   C denylist          : 내부 구현 용어(run.sh GLOBAL_ANSWER_DENYLIST와 동일 목록) 노출
#   D ask-identifier    : 사용자에게 shortCode 등 내부 식별자를 되물음
#   E empty-or-error    : http!=200, 빈/초단답, limitReached 발생
#
# 사용법: ./sweep-screen.sh [sweep-results.jsonl]
set -euo pipefail
cd "$(dirname "$0")"

IN="${1:-sweep-results.jsonl}"
[[ -f "$IN" ]] || {
	echo "오류: ${IN} 없음"
	exit 1
}

DENYLIST='list_posts|aggregate_posts|search_posts|get_comments|get_author|list_brands|groupBy|reachMultiple|viewsSampleCount|minSample|sponsorship 인자|shortCode'
REFUSAL='도와드릴 수 없|도와드리기 어|제공할 수 없|제공해 드릴 수 없|지원하지 않|범위를 벗어|범위 밖|알 수 없습니다|불가능합니다|할 수 없어요|할 수 없습니다|어려워요|어렵습니다'
ASK_ID='shortCode를 알려|숏코드를 알려|ID를 알려주|식별자를 알려'

jq -c '.' "$IN" | while IFS= read -r row; do
	id=$(jq -r '.id' <<<"$row")
	turn=$(jq -r '.turn' <<<"$row")
	category=$(jq -r '.category' <<<"$row")
	http=$(jq -r '.http' <<<"$row")
	answer=$(jq -r '.answer' <<<"$row")
	tools=$(jq -r '.toolCalls | length' <<<"$row")
	limit_reached=$(jq -r '.limitReached // ""' <<<"$row")
	flags=""

	# E: 전송 실패·빈 답·예산 절단
	if [[ "$http" != "200" ]]; then
		flags="${flags}E(http=${http}) "
	elif [[ ${#answer} -lt 20 ]]; then
		flags="${flags}E(len=${#answer}) "
	fi
	[[ -n "$limit_reached" ]] && flags="${flags}E(limit=${limit_reached}) "

	# A: 도메인 안 질문의 거절 문구 (impossible 부류는 거절이 정답이라 제외)
	if [[ "$category" != "impossible" ]] && grep -qE "$REFUSAL" <<<"$answer"; then
		flags="${flags}A(refusal) "
	fi

	# B: 툴 0회 + 수치/계정명 (숫자+단위 또는 @핸들) - 날조 의심
	if [[ "$tools" == "0" && "$http" == "200" ]]; then
		if grep -qE '[0-9][0-9,.]*[ ]?(개|건|명|회|%|위|배)|@[A-Za-z0-9_.]{3,}' <<<"$answer"; then
			if [[ "$turn" == "1" ]]; then
				flags="${flags}B(fabrication?) "
			else
				flags="${flags}B(chain-reuse?) "
			fi
		fi
	fi

	# C: 내부 용어 유출
	hit=$(grep -oE "$DENYLIST" <<<"$answer" | head -1 || true)
	[[ -n "$hit" ]] && flags="${flags}C(${hit}) "

	# D: 식별자 되물음
	grep -qE "$ASK_ID" <<<"$answer" && flags="${flags}D(ask-id) "

	if [[ -n "$flags" ]]; then
		printf '%s|t%s|%s|%s|%s\n' "$id" "$turn" "$category" "$flags" "$(jq -r '.question' <<<"$row")"
	fi
done

echo
echo "== 통계 =="
total=$(wc -l <"$IN" | tr -d ' ')
echo "총 턴: $total"
echo "카테고리별 평균 답변 길이:"
jq -sr 'group_by(.category)[] | "\(.[0].category)\t\(length)턴\t평균 \((map(.answer | length) | add / length) | floor)자"' "$IN"
echo "툴 호출 분포:"
jq -sr 'group_by(.toolCalls | length)[] | "\(.[0].toolCalls | length)회: \(length)턴"' "$IN"

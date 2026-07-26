#!/usr/bin/env bash
# 뷰 SQL의 analytics 스키마 참조를 analytics_dev로 치환하는 필터 (태스크 K — dev 스테이징 전용).
# 규칙: 앞 문자가 작은따옴표·식별자 문자가 아닐 때만 치환 — app_setting 키('analytics.recent-window' 등)
#       문자열 리터럴을 보존한다. 달러 인용($$) 함수 본문은 치환 대상(내부의 따옴표 키는 역시 보존).
# 치환 누락은 dev 계정 권한(analytics 스키마 쓰기 불가)과 cd-dev의 잔존 참조 검사가 이중으로 잡는다.
set -euo pipefail
sed -E \
  -e "s/(^|[^'[:alnum:]_])analytics\./\1analytics_dev./g" \
  -e "s/CREATE SCHEMA IF NOT EXISTS analytics;/CREATE SCHEMA IF NOT EXISTS analytics_dev;/"

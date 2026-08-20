#!/usr/bin/env python3
# ons-discord-relay.py 포맷 로직 단위 테스트 — 서버 기동·네트워크 없이 순수 함수만 검증.
# 실행: python3 deploy/ons-discord-relay.test.py  (실패 시 AssertionError로 종료 코드 1)
import importlib.util
import json
import os

os.environ.setdefault("DISCORD_WEBHOOK_URL", "http://unused.invalid")
os.environ.setdefault("ONS_RELAY_TOKEN", "unused")
_spec = importlib.util.spec_from_file_location(
	"relay", os.path.join(os.path.dirname(os.path.abspath(__file__)), "ons-discord-relay.py"))
relay = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(relay)

# 2026-08-12 운영 실물 페이로드(축약) — containerName 차원이 본문에 표기되어야 한다
firing = {
	"title": "hypenow-container-down",
	"body": "도커 컨테이너가 3분 이상 다운 상태입니다 (containerName 차원 확인).",
	"type": "OK_TO_FIRING",
	"severity": "CRITICAL",
	"alarmMetaData": [{
		"status": "FIRING",
		"dimensions": [{"containerName": "monitoring"}],
	}],
}
out = relay.format_alarm(firing, "")
assert out == ("🚨 **hypenow-container-down**\n"
	"도커 컨테이너가 3분 이상 다운 상태입니다 (containerName 차원 확인).\n"
	"📍 containerName=monitoring"), out

# 해소(FIRING_TO_OK)도 접미사와 차원이 함께 표기된다
resolved = dict(firing, type="FIRING_TO_OK")
out = relay.format_alarm(resolved, "")
assert "(해소됨 ✅)" in out and "📍 containerName=monitoring" in out, out

# 차원 없는 알람(구형·비알람 메시지)은 기존 포맷 그대로 — 📍 줄 없음
plain = {"title": "hypenow-disk-high", "body": "본문", "severity": "WARNING", "type": "OK_TO_FIRING"}
out = relay.format_alarm(plain, "")
assert out == "⚠️ **hypenow-disk-high**\n본문", out

# 여러 스트림·중복 차원은 순서 유지로 합치고, 형태가 이상한 항목은 조용히 건너뛴다
messy = {
	"title": "t", "body": "b", "severity": "CRITICAL",
	"alarmMetaData": [
		{"dimensions": [{"containerName": "was"}, {"containerName": "redis"}]},
		"이상한 항목",
		{"dimensions": [{"containerName": "was"}, "이상한 차원", {"host": "hypenow-api"}]},
	],
}
out = relay.format_alarm(messy, "")
assert out.endswith("📍 containerName=was, containerName=redis, host=hypenow-api"), out

# title/body 없는 원문 폴백도 기존 동작 유지
out = relay.format_alarm({}, "원문 그대로")
assert out == "🔔 **OCI 알림**\n원문 그대로", out

# JSON 최상위가 dict가 아니어도(배열 등) 예외 없이 원문 폴백으로 발송된다 — 유실 방지
out = relay.format_alarm([1, 2], "원문 폴백")
assert out == "🔔 **OCI 알림**\n원문 폴백", out

# type이 문자열이 아니어도 예외 없이 처리된다(해소 접미사만 안 붙음)
out = relay.format_alarm({"title": "t", "body": "b", "type": 123, "severity": "CRITICAL"}, "")
assert out == "🚨 **t**\nb", out

# --- ONS at-least-once 중복 전달 방어 (2026-08-19 유령 알람 사례) ---
# 동일 (dedupeKey, type)이 TTL 내 다시 오면 중복 — 첫 수신만 게시 대상
relay._recent_deliveries.clear()
dup = {"dedupeKey": "ddd1544b-f02b-4940-8c8b-8ea9c360cf60", "type": "OK_TO_FIRING"}
assert relay.is_duplicate(dup, now=1000.0) is False
assert relay.is_duplicate(dup, now=1010.0) is True

# 같은 dedupeKey라도 type이 다르면(FIRING_TO_OK 등 상태 전이) 중복이 아니다
assert relay.is_duplicate(dict(dup, type="FIRING_TO_OK"), now=1020.0) is False

# TTL(30분)이 지나면 같은 키도 다시 게시되고, 만료 항목은 판정 시 캐시에서 청소된다
relay._recent_deliveries[("옛키", "OK_TO_FIRING")] = 0.0
assert relay.is_duplicate(dup, now=1000.0 + relay.DEDUPE_TTL_SECONDS + 1) is False
assert ("옛키", "OK_TO_FIRING") not in relay._recent_deliveries

# dedupeKey가 없거나 body가 dict가 아니면 판정 불가 — 항상 게시(유실 방지 우선)
assert relay.is_duplicate({"type": "OK_TO_FIRING"}, now=2000.0) is False
assert relay.is_duplicate({"type": "OK_TO_FIRING"}, now=2001.0) is False
assert relay.is_duplicate([1, 2], now=2002.0) is False

# handle_message 통합: 동일 페이로드 2회 수신 시 디스코드 게시는 1회만
relay._recent_deliveries.clear()
_posted = []
_orig_post = relay.post_discord
relay.post_discord = _posted.append
try:
	raw_dup = json.dumps({
		"dedupeKey": "ddd1544b-f02b-4940-8c8b-8ea9c360cf60", "type": "OK_TO_FIRING",
		"title": "hypenow-api-unreachable", "body": "본문", "severity": "CRITICAL",
	})
	relay.Handler.handle_message(object(), raw_dup)
	relay.Handler.handle_message(object(), raw_dup)
	assert len(_posted) == 1, _posted
finally:
	relay.post_discord = _orig_post

print("전체 통과")

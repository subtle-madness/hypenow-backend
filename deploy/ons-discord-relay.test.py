#!/usr/bin/env python3
# ons-discord-relay.py 포맷 로직 단위 테스트 — 서버 기동·네트워크 없이 순수 함수만 검증.
# 실행: python3 deploy/ons-discord-relay.test.py  (실패 시 AssertionError로 종료 코드 1)
import importlib.util
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

print("전체 통과")

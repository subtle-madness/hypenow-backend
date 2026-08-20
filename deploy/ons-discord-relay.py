#!/usr/bin/env python3
# OCI ONS(CUSTOM_HTTPS 구독) → 디스코드 웹훅 릴레이.
# ONS가 SLACK 프로토콜 엔드포인트를 hooks.slack.com만 허용하게 되어(2026-07 실측) 디스코드
# 직결이 불가 — 알람 JSON을 받아 디스코드 메시지 형식({"content": ...})으로 변환 전달한다.
# 경로 토큰(ONS_RELAY_TOKEN)이 맞는 요청만 처리. 구독 확인(SubscriptionConfirmation)은
# 본문의 확인 URL을 자동 방문해 셀프 컨펌한다. 표준 라이브러리만 사용(별도 이미지 빌드 없음).
import json
import os
import re
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

WEBHOOK = os.environ["DISCORD_WEBHOOK_URL"]
TOKEN = os.environ["ONS_RELAY_TOKEN"]

SEVERITY_EMOJI = {"CRITICAL": "🚨", "ERROR": "🚨", "WARNING": "⚠️", "INFO": "ℹ️"}

# ONS는 at-least-once 전달이라 같은 알람이 재전달될 수 있다(2026-08-19 실측: FIRING_TO_OK 뒤에
# 동일 dedupeKey·timestamp의 OK_TO_FIRING이 재도착 → 디스코드에 유령 알람). (dedupeKey, type)
# 기준 TTL 내 재수신은 게시를 생략한다. 릴레이는 단일 프로세스라 메모리 dict로 충분.
DEDUPE_TTL_SECONDS = 30 * 60
_recent_deliveries: dict[tuple[str, str], float] = {}


def is_duplicate(body, now: float | None = None) -> bool:
	"""TTL 내 동일 (dedupeKey, type) 재수신 여부. dedupeKey가 없으면 판정 불가 — 게시 우선."""
	if not isinstance(body, dict) or not body.get("dedupeKey"):
		return False
	if now is None:
		now = time.monotonic()
	for key, seen_at in list(_recent_deliveries.items()):
		if now - seen_at > DEDUPE_TTL_SECONDS:
			del _recent_deliveries[key]
	ident = (str(body["dedupeKey"]), str(body.get("type") or ""))
	if ident in _recent_deliveries:
		return True
	_recent_deliveries[ident] = now
	return False


def post_discord(content: str) -> None:
	req = urllib.request.Request(
		WEBHOOK, data=json.dumps({"content": content[:1900]}).encode(),
		headers={"Content-Type": "application/json", "User-Agent": "hypenow-ons-relay"})
	urllib.request.urlopen(req, timeout=10).read()


def alarm_dimensions(body: dict) -> list[str]:
	"""alarmMetaData[].dimensions[]의 키=값 쌍을 순서 유지·중복 제거로 수집.
	형태가 예상과 달라도 조용히 건너뛴다 — 차원 표기는 부가 정보라 본문 발송을 막지 않는다."""
	pairs = []
	meta = body.get("alarmMetaData")
	for entry in meta if isinstance(meta, list) else []:
		dims = entry.get("dimensions") if isinstance(entry, dict) else None
		for dim in dims if isinstance(dims, list) else []:
			if not isinstance(dim, dict):
				continue
			for key, value in dim.items():
				pair = f"{key}={value}"
				if pair not in pairs:
					pairs.append(pair)
	return pairs


def format_alarm(body: dict, raw: str) -> str:
	if not isinstance(body, dict):  # JSON 최상위가 dict가 아니면(배열 등) 원문 폴백 — 발송 유실 방지
		body = {}
	title = body.get("title") or "OCI 알림"
	text = body.get("body") or raw[:1500]
	emoji = SEVERITY_EMOJI.get(str(body.get("severity", "")).upper(), "🔔")
	state = str(body.get("type") or "")  # 예: OK_TO_FIRING / FIRING_TO_OK — 비문자열 방어
	suffix = " (해소됨 ✅)" if "TO_OK" in state else ""
	dims = alarm_dimensions(body)
	dim_line = f"\n📍 {', '.join(dims)}" if dims else ""
	return f"{emoji} **{title}**{suffix}\n{text}{dim_line}"


class Handler(BaseHTTPRequestHandler):

	def do_POST(self):
		if TOKEN not in self.path:
			self.send_response(404)
			self.end_headers()
			return
		raw = self.rfile.read(int(self.headers.get("Content-Length") or 0)).decode("utf-8", "replace")
		print(f"수신: {raw[:2000]}", flush=True)
		# 어떤 처리 실패든 200을 먼저 확정 — ONS 재시도 폭주 방지(실패는 로그로 추적)
		self.send_response(200)
		self.end_headers()
		try:
			self.handle_message(raw)
		except Exception as e:  # noqa: BLE001 — 릴레이는 죽지 않는 게 우선
			print(f"처리 실패: {e}", flush=True)

	def handle_message(self, raw: str) -> None:
		try:
			body = json.loads(raw)
		except ValueError:
			body = {}
		# 구독 확인 메시지 — 본문 내 확인 URL을 방문하면 구독이 ACTIVE로 전환된다
		if "confirmation" in raw.lower():
			url = re.search(r'https://[^"\s\\]+', raw)
			if url:
				urllib.request.urlopen(url.group(0), timeout=10).read()
				print(f"구독 확인 방문: {url.group(0)}", flush=True)
				post_discord("✅ OCI 알람 구독이 연결되었습니다 (hypenow-alerts)")
				return
		if is_duplicate(body):
			print(f"중복 수신 생략: dedupeKey={body.get('dedupeKey')} type={body.get('type')}", flush=True)
			return
		post_discord(format_alarm(body, raw))

	def log_message(self, *args):  # 기본 액세스 로그 소음 억제 — 수신 본문은 위에서 직접 로깅
		pass


if __name__ == "__main__":  # 테스트가 import할 수 있게 서버 기동은 스크립트 실행 시에만
	HTTPServer(("0.0.0.0", 9099), Handler).serve_forever()

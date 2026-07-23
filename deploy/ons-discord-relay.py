#!/usr/bin/env python3
# OCI ONS(CUSTOM_HTTPS 구독) → 디스코드 웹훅 릴레이.
# ONS가 SLACK 프로토콜 엔드포인트를 hooks.slack.com만 허용하게 되어(2026-07 실측) 디스코드
# 직결이 불가 — 알람 JSON을 받아 디스코드 메시지 형식({"content": ...})으로 변환 전달한다.
# 경로 토큰(ONS_RELAY_TOKEN)이 맞는 요청만 처리. 구독 확인(SubscriptionConfirmation)은
# 본문의 확인 URL을 자동 방문해 셀프 컨펌한다. 표준 라이브러리만 사용(별도 이미지 빌드 없음).
import json
import os
import re
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

WEBHOOK = os.environ["DISCORD_WEBHOOK_URL"]
TOKEN = os.environ["ONS_RELAY_TOKEN"]

SEVERITY_EMOJI = {"CRITICAL": "🚨", "ERROR": "🚨", "WARNING": "⚠️", "INFO": "ℹ️"}


def post_discord(content: str) -> None:
	req = urllib.request.Request(
		WEBHOOK, data=json.dumps({"content": content[:1900]}).encode(),
		headers={"Content-Type": "application/json", "User-Agent": "hypenow-ons-relay"})
	urllib.request.urlopen(req, timeout=10).read()


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
		title = body.get("title") or "OCI 알림"
		text = body.get("body") or raw[:1500]
		emoji = SEVERITY_EMOJI.get(str(body.get("severity", "")).upper(), "🔔")
		state = body.get("type", "")  # 예: OK_TO_FIRING / FIRING_TO_OK
		suffix = " (해소됨 ✅)" if "TO_OK" in state else ""
		post_discord(f"{emoji} **{title}**{suffix}\n{text}")

	def log_message(self, *args):  # 기본 액세스 로그 소음 억제 — 수신 본문은 위에서 직접 로깅
		pass


HTTPServer(("0.0.0.0", 9099), Handler).serve_forever()

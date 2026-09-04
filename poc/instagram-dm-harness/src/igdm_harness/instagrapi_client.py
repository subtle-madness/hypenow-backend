"""실제 instagrapi 래퍼. 세션(계정당 1회 로그인·dump_settings)·기기·프록시·DM·도착확인.

⚠️ 실계정 네트워크 I/O. 자동 재시도·복구 없음(하드 신호는 runner가 死로 처리).
⚠️ 실행 전 반드시 드라이런(DryRunClient)으로 파이프라인 검증.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, Optional

from instagrapi import Client as IGClient
from instagrapi.exceptions import (
    ChallengeRequired, FeedbackRequired, LoginRequired, PleaseWaitFewMinutes,
    ClientError,
)

from .signals import ActionResult, DeliveryStatus, RawSignal


class InstagrapiClient:
    """Client 프로토콜 구현. 계정당 IGClient 인스턴스를 세션 디렉토리에 영구화."""

    def __init__(self, session_dir: str, device_profiles: Dict[str, Dict]) -> None:
        self._session_dir = Path(session_dir)
        self._session_dir.mkdir(parents=True, exist_ok=True)
        self._device_profiles = device_profiles      # {alias: build_device_profile(...)}
        self._clients: Dict[str, IGClient] = {}
        self._creds: Dict[str, tuple] = {}            # {alias: (username, password)}
        self._dummy_clients: Dict[str, IGClient] = {}

    def register_credentials(self, alias: str, username: str, password: str) -> None:
        self._creds[alias] = (username, password)

    def register_dummy(self, username: str, password: str) -> None:
        cl = IGClient()
        cl.login(username, password)
        self._dummy_clients[username] = cl

    def _session_path(self, alias: str) -> Path:
        return self._session_dir / f"{alias}.session.json"

    def ensure_session(self, account_alias: str, device_profile: Dict, proxy_url: Optional[str]) -> None:
        cl = IGClient()
        if proxy_url:
            cl.set_proxy(proxy_url)
        prof = self._device_profiles.get(account_alias) or device_profile
        if prof:
            cl.set_settings({
                "device_settings": prof.get("device_settings", {}),
                "uuids": prof.get("uuids", {}),
                "locale": prof.get("locale", "ko_KR"),
                "country": prof.get("country", "KR"),
                "user_agent": prof.get("user_agent", ""),
            })
        path = self._session_path(account_alias)
        username, password = self._creds[account_alias]
        if path.exists():
            cl.load_settings(path)
            cl.login(username, password)   # 세션 있으면 재검증만, 없으면 정식 로그인
        else:
            cl.login(username, password)   # 계정당 1회 정식 로그인
            cl.dump_settings(path)
        self._clients[account_alias] = cl

    def send_dm(self, account_alias: str, recipient_username: str, text: str) -> ActionResult:
        cl = self._clients[account_alias]
        try:
            uid = cl.user_id_from_username(recipient_username)
            cl.direct_send(text, user_ids=[uid])
            return ActionResult(ok=True, signal=None, raw_response="sent")
        except Exception as exc:  # noqa: BLE001 — 신호는 detector가 분류
            return ActionResult(ok=False, signal=self._to_signal(exc),
                                raw_response=str(exc))

    def do_non_dm(self, account_alias: str) -> ActionResult:
        cl = self._clients[account_alias]
        try:
            medias = cl.explore_medias(amount=3)   # 피드 열람(가벼운 비DM 활동)
            for m in medias[:1]:
                cl.media_like(m.id)                # 소수 좋아요
            return ActionResult(ok=True, signal=None, raw_response="non_dm ok")
        except Exception as exc:  # noqa: BLE001
            return ActionResult(ok=False, signal=self._to_signal(exc),
                                raw_response=str(exc))

    def check_delivery(self, dummy_username: str, from_username: str, text: str) -> DeliveryStatus:
        cl = self._dummy_clients.get(dummy_username)
        if cl is None:
            return DeliveryStatus.UNKNOWN
        try:
            # 받은함 + 요청함(pending) 스레드를 훑어 from_username에서 온 text 존재 확인
            threads = cl.direct_threads(amount=20) + cl.direct_pending_inbox(amount=20)
            for t in threads:
                senders = {u.username for u in t.users}
                if from_username in senders:
                    for msg in getattr(t, "messages", []) or []:
                        if getattr(msg, "text", None) == text:
                            return DeliveryStatus.DELIVERED
            return DeliveryStatus.NOT_DELIVERED
        except Exception:  # noqa: BLE001
            return DeliveryStatus.UNKNOWN

    @staticmethod
    def _to_signal(exc: Exception) -> RawSignal:
        status = None
        response = getattr(exc, "response", None)
        if response is not None:
            status = getattr(response, "status_code", None)
        return RawSignal(exc_name=type(exc).__name__, status_code=status, message=str(exc))

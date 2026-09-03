"""Client 프로토콜 + DryRunClient. instagrapi 미의존 → runner를 네트워크 없이 테스트.

실제 네트워크 구현은 instagrapi_client.InstagrapiClient (별 모듈, instagrapi 의존).
"""

from __future__ import annotations

from typing import Dict, Optional, Protocol, Tuple

from .signals import ActionResult, DeliveryStatus, RawSignal


class Client(Protocol):
    def ensure_session(self, account_alias: str, device_profile: Dict, proxy_url: Optional[str]) -> None:
        """세션 로드 또는 계정당 1회 로그인 후 dump_settings. instagrapi 담당."""
        ...

    def send_dm(self, account_alias: str, recipient_username: str, text: str) -> ActionResult:
        ...

    def do_non_dm(self, account_alias: str) -> ActionResult:
        """비DM 활동(피드 열람·좋아요 소수)."""
        ...

    def check_delivery(self, dummy_username: str, from_username: str, text: str) -> DeliveryStatus:
        """수신 더미에 로그인해 도착/사일런트드롭 확인."""
        ...


class DryRunClient:
    """실발송 0. 호출만 기록. scripted로 특정 (account, nth_send)에 신호 주입."""

    def __init__(self, scripted: Optional[Dict[Tuple[str, int], RawSignal]] = None) -> None:
        self.scripted = scripted or {}
        self.sent: list[Tuple[str, str, str]] = []
        self.non_dm_calls: list[str] = []
        self.sessions: list[str] = []
        self._send_counts: Dict[str, int] = {}

    def ensure_session(self, account_alias: str, device_profile: Dict, proxy_url: Optional[str]) -> None:
        self.sessions.append(account_alias)

    def send_dm(self, account_alias: str, recipient_username: str, text: str) -> ActionResult:
        n = self._send_counts.get(account_alias, 0) + 1
        self._send_counts[account_alias] = n
        self.sent.append((account_alias, recipient_username, text))
        sig = self.scripted.get((account_alias, n))
        if sig is not None:
            return ActionResult(ok=False, signal=sig, raw_response=f"dryrun-scripted:{sig.exc_name}")
        return ActionResult(ok=True, signal=None, raw_response="dryrun-ok")

    def do_non_dm(self, account_alias: str) -> ActionResult:
        self.non_dm_calls.append(account_alias)
        return ActionResult(ok=True, signal=None, raw_response="dryrun-nondm")

    def check_delivery(self, dummy_username: str, from_username: str, text: str) -> DeliveryStatus:
        return DeliveryStatus.DELIVERED

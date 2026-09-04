"""밴 신호 분류·死 판정·함대 서킷브레이커. instagrapi 미의존."""

from __future__ import annotations

from collections import deque
from typing import Deque, Tuple

from .signals import RawSignal, SignalGrade

# 종료(死) 예외 클래스명
_TERMINAL_EXC = {
    "ChallengeRequired",
    "AccountDisabled",
    "UserNotFound",       # 계정 소멸
    "SentryBlock",        # 인스타 차단(강)
}
# 액션차단 예외 클래스명
_ACTION_BLOCK_EXC = {
    "FeedbackRequired",
}
# 일시 예외 클래스명
_TRANSIENT_EXC = {
    "PleaseWaitFewMinutes",
    "ClientThrottledError",
    "RateLimitError",
}

DEFAULT_LOGIN_REQUIRED_DEATH_STREAK = 2


def classify(
    sig: RawSignal,
    login_required_death_streak: int = DEFAULT_LOGIN_REQUIRED_DEATH_STREAK,
) -> SignalGrade:
    """원신호를 3단계로 분류. 미상 예외는 안전 우선으로 ACTION_BLOCK(계정 정지)."""
    exc = sig.exc_name

    if exc == "LoginRequired":
        if sig.login_required_streak >= login_required_death_streak:
            return SignalGrade.TERMINAL
        return SignalGrade.TRANSIENT

    if exc in _TERMINAL_EXC:
        return SignalGrade.TERMINAL
    if exc in _ACTION_BLOCK_EXC:
        return SignalGrade.ACTION_BLOCK
    if exc in _TRANSIENT_EXC:
        return SignalGrade.TRANSIENT

    if sig.status_code == 429:
        return SignalGrade.TRANSIENT
    if sig.status_code is not None and sig.status_code >= 500:
        return SignalGrade.TRANSIENT

    if exc is None:
        return SignalGrade.OK

    # 미상 예외: 계속 두드리지 않도록 계정 정지. 원문은 ledger에 남겨 사후 재분류.
    return SignalGrade.ACTION_BLOCK


def is_death(grade: SignalGrade) -> bool:
    """POC 규칙: 첫 하드 신호(액션차단·종료)를 死로 확정."""
    return grade in (SignalGrade.ACTION_BLOCK, SignalGrade.TERMINAL)


class CircuitBreaker:
    """짧은 창에 서로 다른 N개 계정이 하드 신호 → 함대 전체 정지(계통 문제 방어)."""

    def __init__(self, window_seconds: float = 900.0, threshold: int = 2) -> None:
        self._window = window_seconds
        self._threshold = threshold
        self._events: Deque[Tuple[str, float]] = deque()  # (account_alias, ts)

    def record_hard_signal(self, account_alias: str, now: float) -> None:
        self._events.append((account_alias, now))

    def is_tripped(self, now: float) -> bool:
        cutoff = now - self._window
        # 창 밖 이벤트 제거
        while self._events and self._events[0][1] < cutoff:
            self._events.popleft()
        distinct = {alias for alias, ts in self._events if ts >= cutoff}
        return len(distinct) >= self._threshold

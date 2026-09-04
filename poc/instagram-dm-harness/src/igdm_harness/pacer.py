"""페이싱: 활성시간 게이트·Δt 지터·계정당 시간당 레이트 상한·비DM 삽입. instagrapi 미의존."""

from __future__ import annotations

import random
from collections import deque
from datetime import datetime
from typing import Deque
from zoneinfo import ZoneInfo


def is_active_hours(dt: datetime, tz: str, start_hour: int, end_hour: int) -> bool:
    """dt(타임존 aware)를 tz로 변환해 [start_hour, end_hour) 안이면 True. KST 주간 한정용."""
    local = dt.astimezone(ZoneInfo(tz))
    return start_hour <= local.hour < end_hour


def jitter_delay_seconds(rng: random.Random, min_seconds: int, max_seconds: int) -> float:
    """발송 간 간격 지터. 등간격 금지(Δt가 탐지 피처)."""
    return rng.uniform(min_seconds, max_seconds)


def should_insert_non_dm(rng: random.Random, ratio: float) -> bool:
    """DM 사이에 비DM 액션(피드·좋아요)을 섞을지. ratio=0이면 안 함, 1이면 항상."""
    if ratio <= 0.0:
        return False
    if ratio >= 1.0:
        return True
    return rng.random() < ratio


class RateLimiter:
    """계정당 최근 1시간 발송수를 세어 상한 초과를 차단."""

    def __init__(self, max_per_hour: int) -> None:
        self._max = max_per_hour
        self._sends: Deque[float] = deque()

    def _prune(self, now: float) -> None:
        cutoff = now - 3600.0
        while self._sends and self._sends[0] < cutoff:
            self._sends.popleft()

    def record(self, now: float) -> None:
        self._sends.append(now)

    def allowed(self, now: float) -> bool:
        self._prune(now)
        return len(self._sends) < self._max

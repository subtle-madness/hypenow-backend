"""밴 신호 등급과 액션 결과 타입. instagrapi에 의존하지 않는다(테스트 격리)."""

from __future__ import annotations

import enum
from dataclasses import dataclass, field
from typing import Optional


class SignalGrade(enum.Enum):
    OK = "ok"                    # 정상
    TRANSIENT = "transient"      # 일시: 429/PleaseWait — 백오프(死 아님)
    ACTION_BLOCK = "action_block"  # 액션차단: FeedbackRequired — 계정 발송 중단
    TERMINAL = "terminal"        # 종료(死): ChallengeRequired/계정비활성/LoginRequired 반복


class DeliveryStatus(enum.Enum):
    DELIVERED = "delivered"
    NOT_DELIVERED = "not_delivered"
    UNKNOWN = "unknown"


@dataclass
class RawSignal:
    """분류기에 넘길 정규화된 원신호. instagrapi 예외를 이 형태로 옮겨 분류한다."""
    exc_name: Optional[str] = None      # 예외 클래스명(예: "ChallengeRequired")
    status_code: Optional[int] = None   # HTTP 상태(있으면)
    message: str = ""
    login_required_streak: int = 0      # 연속 LoginRequired 횟수(반복=死 판정용)


@dataclass
class ActionResult:
    """한 액션(DM 발송·비DM)의 결과."""
    ok: bool
    signal: Optional[RawSignal] = None
    raw_response: str = ""
    grade: Optional[SignalGrade] = field(default=None)  # detector가 채움

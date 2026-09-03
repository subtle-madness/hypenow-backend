"""수신자 화이트리스트 가드 — 실제 사람 오발송을 코드 레벨에서 원천 차단(협상 불가 안전선).

sender는 어떤 발송이든 이 함수를 먼저 통과해야 한다.
"""

from __future__ import annotations

from typing import Iterable, Set


class RecipientNotAllowedError(Exception):
    """수신자가 통제 더미 화이트리스트에 없음 — 발송 차단."""


def normalize_username(username: str) -> str:
    return username.strip().lstrip("@").lower()


def build_allowlist(usernames: Iterable[str]) -> Set[str]:
    return {normalize_username(u) for u in usernames}


def assert_recipient_allowed(recipient_username: str, allowlist: Set[str]) -> None:
    """정규화된 수신자가 화이트리스트에 없으면 RecipientNotAllowedError.

    allowlist는 build_allowlist로 정규화된 집합을 넘기는 것을 권장.
    """
    norm = normalize_username(recipient_username)
    if not norm:
        raise RecipientNotAllowedError("빈 수신자 — 발송 차단")
    # allowlist가 정규화 안 됐을 수 있으니 방어적으로 정규화 비교
    normalized_allow = {normalize_username(a) for a in allowlist}
    if norm not in normalized_allow:
        raise RecipientNotAllowedError(
            f"수신자 '{recipient_username}' 는 통제 더미 화이트리스트에 없음 — 발송 차단"
        )

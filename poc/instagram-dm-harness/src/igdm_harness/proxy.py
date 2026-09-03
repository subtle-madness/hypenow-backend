"""계정:프록시 출구 1:1 미중첩 배정. 순수. 설계 §3 ④.

프록시 URL 자체는 config.proxies에 sticky/geo 파라미터까지 포함해 넣는다
(벤더별 sticky 세션 문법이 달라 조립 대신 config에서 완성형으로 받는다).
수신 더미엔 프록시를 배정하지 않는다.
"""

from __future__ import annotations

from typing import Dict, List

from .config import SenderAccount


class ProxyAssignmentError(Exception):
    pass


def assign_proxies(senders: List[SenderAccount], proxies: Dict[str, str]) -> Dict[str, str]:
    """{account_alias: proxy_url}. 출구 누락·중복이면 예외(config 검증과 이중 방어)."""
    result: Dict[str, str] = {}
    used = set()
    for s in senders:
        if s.proxy_exit not in proxies:
            raise ProxyAssignmentError(f"프록시 출구 '{s.proxy_exit}' (계정 {s.alias}) 없음")
        if s.proxy_exit in used:
            raise ProxyAssignmentError(f"프록시 출구 '{s.proxy_exit}' 중복 배정 — 1:1 위반")
        used.add(s.proxy_exit)
        result[s.alias] = proxies[s.proxy_exit]
    return result

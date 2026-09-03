"""실행 config 로더·검증. 실험의 단일 정본. 설계 §3·§5."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Set

import yaml

from .guard import build_allowlist


class ConfigError(Exception):
    """config 검증 실패."""


@dataclass
class Params:
    active_start_hour: int = 10
    active_end_hour: int = 22
    timezone: str = "Asia/Seoul"
    jitter_min_seconds: int = 60
    jitter_max_seconds: int = 300
    max_sends_per_hour: int = 21
    non_dm_ratio: float = 0.3
    circuit_window_seconds: int = 900
    circuit_threshold: int = 2
    login_required_death_streak: int = 2
    post_send_observe_days: int = 3


@dataclass
class SenderAccount:
    alias: str
    username: str
    password: str
    verification: str      # phone / email
    arm: str               # send / control
    proxy_exit: str


@dataclass
class DummyRecipient:
    alias: str
    username: str
    password: str


@dataclass
class MessageVariant:
    id: str
    text: str


@dataclass
class HarnessConfig:
    dry_run: bool
    ledger_path: str
    session_dir: str
    kill_switch_path: str
    params: Params
    proxies: Dict[str, str]
    senders: List[SenderAccount]
    dummies: List[DummyRecipient]
    messages: List[MessageVariant]
    allowlist: Set[str] = field(default_factory=set)


def load_config(path) -> HarnessConfig:
    raw = yaml.safe_load(Path(path).read_text())
    if not isinstance(raw, dict):
        raise ConfigError("config 최상위는 매핑이어야 함")

    p = raw.get("params", {}) or {}
    params = Params(**p)
    if params.active_start_hour >= params.active_end_hour:
        raise ConfigError("활성시간: active_start_hour < active_end_hour 여야 함")
    if params.jitter_min_seconds > params.jitter_max_seconds:
        raise ConfigError("지터: min <= max 여야 함")

    proxies: Dict[str, str] = raw.get("proxies", {}) or {}
    if not proxies:
        raise ConfigError("프록시 출구가 하나도 없음")

    senders = [SenderAccount(**s) for s in (raw.get("senders") or [])]
    if not senders:
        raise ConfigError("발송 계정이 하나도 없음")

    # 프록시 1:1 미중첩 검증
    seen_exits = set()
    for s in senders:
        if s.proxy_exit not in proxies:
            raise ConfigError(f"프록시 출구 '{s.proxy_exit}' (계정 {s.alias})가 proxies에 없음")
        if s.proxy_exit in seen_exits:
            raise ConfigError(f"프록시 출구 '{s.proxy_exit}' 가 중복 배정됨 — 1:1 미중첩 위반")
        seen_exits.add(s.proxy_exit)
        if s.arm not in ("send", "control"):
            raise ConfigError(f"계정 {s.alias}의 arm은 send/control 이어야 함")

    dummies = [DummyRecipient(**d) for d in (raw.get("dummies") or [])]
    if not dummies:
        raise ConfigError("수신 더미가 하나도 없음 — 발송 대상 없음")

    messages = [MessageVariant(**m) for m in (raw.get("messages") or [])]
    if not messages:
        raise ConfigError("발송 문구가 하나도 없음")

    allowlist = build_allowlist(d.username for d in dummies)

    return HarnessConfig(
        dry_run=bool(raw.get("dry_run", True)),
        ledger_path=raw.get("ledger_path", "ledger.db"),
        session_dir=raw.get("session_dir", "sessions"),
        kill_switch_path=raw.get("kill_switch_path", "KILL"),
        params=params,
        proxies=proxies,
        senders=senders,
        dummies=dummies,
        messages=messages,
        allowlist=allowlist,
    )

"""오케스트레이션 루프. 시간·난수·sleep·client 주입으로 결정적 테스트 가능. 설계 §3."""

from __future__ import annotations

import random
from datetime import datetime, timezone
from typing import Callable, Dict, List, Optional

from .client import Client
from .config import HarnessConfig, SenderAccount
from .detector import CircuitBreaker, classify, is_death
from .guard import assert_recipient_allowed
from .killswitch import kill_requested
from .ledger import DeathEvent, Ledger, SendEvent
from .pacer import is_active_hours, should_insert_non_dm
from .signals import RawSignal, SignalGrade


def _iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class _AccountState:
    def __init__(self, sender: SenderAccount) -> None:
        self.sender = sender
        self.alive = True
        self.dm_count = 0
        self.recipient_cursor = 0
        self.first_send_ts: Optional[float] = None
        self.last_send_ts: Optional[float] = None
        self.login_required_streak = 0


class Runner:
    def __init__(
        self,
        config: HarnessConfig,
        ledger: Ledger,
        client: Client,
        clock_dt: Callable[[], datetime],
        clock_ts: Callable[[], float],
        advance: Callable[[], None],
        sleep: Callable[[float], None],
        rng: random.Random,
        max_actions: int = 10_000,
    ) -> None:
        self.config = config
        self.ledger = ledger
        self.client = client
        self.clock_dt = clock_dt
        self.clock_ts = clock_ts
        self.advance = advance
        self.sleep = sleep
        self.rng = rng
        self.max_actions = max_actions
        self.breaker = CircuitBreaker(
            window_seconds=config.params.circuit_window_seconds,
            threshold=config.params.circuit_threshold,
        )
        self.fleet_stopped = False
        self._states: List[_AccountState] = [_AccountState(s) for s in config.senders]

    # --- 세션 준비 ---
    def _prepare(self) -> None:
        from .proxy import assign_proxies
        proxy_map = assign_proxies(self.config.senders, self.config.proxies)
        for st in self._states:
            self.client.ensure_session(
                st.sender.alias, device_profile={}, proxy_url=proxy_map[st.sender.alias]
            )

    def run(self) -> None:
        # 킬 스위치·세션 준비
        if kill_requested(self.config.kill_switch_path):
            self.fleet_stopped = True
            return
        self._prepare()

        actions = 0
        ticks = 0
        while actions < self.max_actions and ticks < self.max_actions:
            if kill_requested(self.config.kill_switch_path):
                self.fleet_stopped = True
                return
            if self.breaker.is_tripped(self.clock_ts()):
                self.fleet_stopped = True
                return
            if all(not st.alive for st in self._states):
                return  # 전 계정 死

            progressed = False
            for st in self._states:
                if not st.alive:
                    continue
                now_dt = self.clock_dt()
                now_ts = self.clock_ts()

                # 활성시간 게이트
                if not is_active_hours(
                    now_dt, self.config.params.timezone,
                    self.config.params.active_start_hour,
                    self.config.params.active_end_hour,
                ):
                    continue

                self._step_account(st, now_dt, now_ts)
                progressed = True
                actions += 1
                if self.breaker.is_tripped(self.clock_ts()):
                    self.fleet_stopped = True
                    return
                if actions >= self.max_actions:
                    return

            # 다음 tick(지터는 실행 경로에서 sleep, 테스트는 no-op sleep + advance)
            self.advance()
            ticks += 1
            if not progressed:
                # 아무도 진행 못 함(전부 비활성 등) — max_actions가 유휴 tick도 상한선으로 막는다
                continue

    def _step_account(self, st: _AccountState, now_dt: datetime, now_ts: float) -> None:
        is_control = st.sender.arm == "control"
        do_non_dm = is_control or should_insert_non_dm(self.rng, self.config.params.non_dm_ratio)

        if do_non_dm:
            res = self.client.do_non_dm(st.sender.alias)
            grade = classify(res.signal or RawSignal(),
                             self.config.params.login_required_death_streak) if res.signal else SignalGrade.OK
            self.ledger.record_send_event(SendEvent(
                ts=_iso(now_dt), account_alias=st.sender.alias, action="non_dm",
                recipient=None, result="success" if res.ok else "fail",
                signal=self._signal_str(res.signal, grade), delivered=None,
                dt_since_prev=None, message_variant=None,
            ))
            self._handle_grade(st, res.signal, grade, now_dt, now_ts, nth=st.dm_count)
            return

        # DM 발송
        recipient = self.config.dummies[st.recipient_cursor % len(self.config.dummies)]
        st.recipient_cursor += 1
        # 안전선: 화이트리스트 코드 차단(실제 사람 오발송 원천 봉쇄)
        assert_recipient_allowed(recipient.username, self.config.allowlist)

        variant = self.rng.choice(self.config.messages)
        dt_prev = None if st.last_send_ts is None else now_ts - st.last_send_ts

        res = self.client.send_dm(st.sender.alias, recipient.username, variant.text)
        st.dm_count += 1
        if st.first_send_ts is None:
            st.first_send_ts = now_ts
        st.last_send_ts = now_ts

        grade = SignalGrade.OK
        if res.signal is not None:
            if res.signal.exc_name == "LoginRequired":
                st.login_required_streak += 1
                res.signal.login_required_streak = st.login_required_streak
            grade = classify(res.signal, self.config.params.login_required_death_streak)
        if res.ok:
            st.login_required_streak = 0

        delivered = None
        if res.ok:
            status = self.client.check_delivery(recipient.username, st.sender.username, variant.text)
            delivered = status.value

        self.ledger.record_send_event(SendEvent(
            ts=_iso(now_dt), account_alias=st.sender.alias, action="dm_send",
            recipient=recipient.username, result="success" if res.ok else "fail",
            signal=self._signal_str(res.signal, grade), delivered=delivered,
            dt_since_prev=dt_prev, message_variant=variant.id,
        ))
        self._handle_grade(st, res.signal, grade, now_dt, now_ts, nth=st.dm_count)

    def _handle_grade(self, st, signal, grade, now_dt, now_ts, nth) -> None:
        if grade == SignalGrade.TRANSIENT:
            self.sleep(0)  # 실행 경로에서 백오프. 테스트는 no-op.
            return
        if is_death(grade):
            survival = 0.0 if st.first_send_ts is None else now_ts - st.first_send_ts
            self.ledger.record_death(DeathEvent(
                account_alias=st.sender.alias, died_at=_iso(now_dt),
                signal=(signal.exc_name if signal and signal.exc_name else grade.value),
                cumulative_sends=st.dm_count, survival_seconds=survival,
                nth_send=nth, raw_response=(signal.message if signal else ""),
            ))
            st.alive = False
            self.breaker.record_hard_signal(st.sender.alias, now_ts)

    @staticmethod
    def _signal_str(signal: Optional[RawSignal], grade: SignalGrade) -> str:
        if signal is None:
            return grade.value
        code = signal.exc_name or (str(signal.status_code) if signal.status_code else "")
        return f"{grade.value}:{code}" if code else grade.value

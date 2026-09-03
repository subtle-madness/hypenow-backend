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
from .pacer import RateLimiter, is_active_hours, jitter_delay_seconds, should_insert_non_dm
from .signals import RawSignal, SignalGrade


def _iso(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class _AccountState:
    def __init__(self, sender: SenderAccount, max_sends_per_hour: int) -> None:
        self.sender = sender
        self.alive = True
        self.dm_count = 0
        self.recipient_cursor = 0
        self.first_send_ts: Optional[float] = None
        self.last_send_ts: Optional[float] = None
        self.login_required_streak = 0
        self.rate = RateLimiter(max_sends_per_hour)


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
        idle_sleep_seconds: float = 300.0,
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
        self.idle_sleep_seconds = idle_sleep_seconds
        self.breaker = CircuitBreaker(
            window_seconds=config.params.circuit_window_seconds,
            threshold=config.params.circuit_threshold,
        )
        self.fleet_stopped = False
        self._states: List[_AccountState] = [
            _AccountState(s, config.params.max_sends_per_hour) for s in config.senders
        ]

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
            ticks += 1
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
                # 사람 킬 스위치를 계정 단위로도 확인(자동 브레이커와 동일 빈도) — 즉시 정지
                if kill_requested(self.config.kill_switch_path):
                    self.fleet_stopped = True
                    return
                now_dt = self.clock_dt()
                now_ts = self.clock_ts()

                # 활성시간 게이트
                if not is_active_hours(
                    now_dt, self.config.params.timezone,
                    self.config.params.active_start_hour,
                    self.config.params.active_end_hour,
                ):
                    continue

                acted = self._step_account(st, now_dt, now_ts)
                if acted:
                    progressed = True
                    actions += 1
                if self.breaker.is_tripped(self.clock_ts()):
                    self.fleet_stopped = True
                    return
                if actions >= self.max_actions:
                    return

            # 다음 tick(지터는 실행 경로에서 sleep, 테스트는 no-op sleep + advance)
            self.advance()
            if not progressed:
                # 아무도 진행 못 함(전부 비활성·레이트 대기 등) — 유휴 대기 후 재확인
                # (live에서 밤새 대기·재개를 흉내. 테스트는 no-op sleep을 주입해 즉시 통과)
                self.sleep(self.idle_sleep_seconds)

    def _step_account(self, st: _AccountState, now_dt: datetime, now_ts: float) -> bool:
        """액션을 실제로 수행했으면 True. 레이트 상한으로 스킵하면 False."""
        is_control = st.sender.arm == "control"
        do_non_dm = is_control or should_insert_non_dm(self.rng, self.config.params.non_dm_ratio)

        if do_non_dm:
            res = self.client.do_non_dm(st.sender.alias)
            grade = self._grade_for(st, res)
            self.ledger.record_send_event(SendEvent(
                ts=_iso(now_dt), account_alias=st.sender.alias, action="non_dm",
                recipient=None, result="success" if res.ok else "fail",
                signal=self._signal_str(res.signal, grade), delivered=None,
                dt_since_prev=None, message_variant=None,
            ))
            self._handle_grade(st, res.signal, grade, now_dt, now_ts, nth=st.dm_count)
            return True

        # DM 발송 — 레이트 상한 강제(§6 발송 전 가드). 초과면 死 아니라 이번 틱 지연.
        if not st.rate.allowed(now_ts):
            return False

        recipient = self.config.dummies[st.recipient_cursor % len(self.config.dummies)]
        st.recipient_cursor += 1
        # 안전선: 화이트리스트 코드 차단(실제 사람 오발송 원천 봉쇄)
        assert_recipient_allowed(recipient.username, self.config.allowlist)

        variant = self.rng.choice(self.config.messages)
        dt_prev = None if st.last_send_ts is None else now_ts - st.last_send_ts

        res = self.client.send_dm(st.sender.alias, recipient.username, variant.text)
        st.dm_count += 1
        st.rate.record(now_ts)
        if st.first_send_ts is None:
            st.first_send_ts = now_ts
        st.last_send_ts = now_ts

        grade = self._grade_for(st, res)

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

        # §5 발송 간 지터(등간격 금지) — 다음 발송 페이싱. 死한 계정엔 불필요.
        if st.alive:
            self.sleep(jitter_delay_seconds(
                self.rng,
                self.config.params.jitter_min_seconds,
                self.config.params.jitter_max_seconds,
            ))
        return True

    def _grade_for(self, st: _AccountState, res) -> SignalGrade:
        """LoginRequired streak를 DM·non_dm 공통으로 누적·리셋하고 등급 산출."""
        if res.signal is None:
            if res.ok:
                st.login_required_streak = 0
            return SignalGrade.OK
        if res.signal.exc_name == "LoginRequired":
            st.login_required_streak += 1
            res.signal.login_required_streak = st.login_required_streak
        grade = classify(res.signal, self.config.params.login_required_death_streak)
        if res.ok:
            st.login_required_streak = 0
        return grade

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

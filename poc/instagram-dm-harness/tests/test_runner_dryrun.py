import random
from datetime import datetime, timezone

from igdm_harness.client import DryRunClient
from igdm_harness.config import (
    HarnessConfig, Params, SenderAccount, DummyRecipient, MessageVariant,
)
from igdm_harness.guard import build_allowlist
from igdm_harness.ledger import Ledger
from igdm_harness.runner import Runner
from igdm_harness.signals import RawSignal


class FakeClock:
    """제어 가능한 시계. 활성시간(KST 주간) 안에서 시작, tick마다 전진."""
    def __init__(self, start_utc: datetime, step_seconds: float = 120.0):
        self._now = start_utc
        self._step = step_seconds

    def now_dt(self) -> datetime:
        return self._now

    def now_ts(self) -> float:
        return self._now.timestamp()

    def advance(self):
        from datetime import timedelta
        self._now = self._now + timedelta(seconds=self._step)


def _config(dummies_n=2, senders=None, dry_run=True):
    senders = senders or [
        SenderAccount("s1", "sender_one", "p", "phone", "send", "exit_1"),
    ]
    dummies = [DummyRecipient(f"d{i}", f"dummy_{i}", "p") for i in range(dummies_n)]
    return HarnessConfig(
        dry_run=dry_run, ledger_path=":memory:", session_dir="sessions",
        kill_switch_path="KILL",
        params=Params(max_sends_per_hour=21, non_dm_ratio=0.0),  # 비DM 끔(발송만 세기 쉽게)
        proxies={"exit_1": "http://a", "exit_2": "http://b"},
        senders=senders, dummies=dummies,
        messages=[MessageVariant("v1", "hi")],
        allowlist=build_allowlist(d.username for d in dummies),
    )


def _runner(cfg, client, clock, rng=None, max_actions=10):
    return Runner(
        config=cfg, ledger=Ledger(cfg.ledger_path), client=client,
        clock_dt=clock.now_dt, clock_ts=clock.now_ts, advance=clock.advance,
        sleep=lambda s: None, rng=rng or random.Random(0),
        max_actions=max_actions,
    )


def _active_clock():
    # 03:00 UTC == 12:00 KST → 활성
    return FakeClock(datetime(2026, 9, 4, 3, 0, tzinfo=timezone.utc))


def test_dryrun_sends_recorded_no_network():
    cfg = _config()
    client = DryRunClient()
    r = _runner(cfg, client, _active_clock(), max_actions=4)
    r.run()
    # 발송이 원장에 기록되고, DryRunClient에만 기록(네트워크 미접촉)
    assert client.sent, "발송이 있어야 함"
    assert r.ledger.cumulative_sends("s1") == len(client.sent)


def test_dryrun_respects_recipient_allowlist():
    # 더미가 아닌 수신자는 애초에 config 더미에서만 뽑히므로 오발송이 없어야 함
    cfg = _config()
    client = DryRunClient()
    r = _runner(cfg, client, _active_clock(), max_actions=6)
    r.run()
    allow = cfg.allowlist
    for _, recipient, _ in client.sent:
        assert recipient in allow


def test_dryrun_inactive_hours_no_send():
    cfg = _config()
    client = DryRunClient()
    # 18:00 UTC == 03:00 KST → 비활성
    clock = FakeClock(datetime(2026, 9, 4, 18, 0, tzinfo=timezone.utc))
    r = _runner(cfg, client, clock, max_actions=5)
    r.run()
    assert client.sent == []  # 비활성시간엔 발송 없음


def test_dryrun_hard_signal_kills_account():
    cfg = _config()
    # s1의 2번째 발송에서 ChallengeRequired
    client = DryRunClient(scripted={("s1", 2): RawSignal(exc_name="ChallengeRequired")})
    r = _runner(cfg, client, _active_clock(), max_actions=10)
    r.run()
    dist = r.ledger.death_cause_distribution()
    assert dist.get("ChallengeRequired") == 1
    # 死 후 추가 발송 없음(2번째에서 死)
    assert r.ledger.cumulative_sends("s1") == 2


def test_dryrun_transient_does_not_kill():
    cfg = _config(dummies_n=5)   # 콜드 1건/더미 상한 — 3건 이상 보려면 더미 5
    client = DryRunClient(scripted={("s1", 2): RawSignal(status_code=429)})
    r = _runner(cfg, client, _active_clock(), max_actions=5)
    r.run()
    assert r.ledger.death_cause_distribution() == {}  # 429는 死 아님
    assert r.ledger.cumulative_sends("s1") >= 3       # 계속 발송


def test_dryrun_circuit_breaker_trips_fleet():
    cfg = _config(senders=[
        SenderAccount("s1", "sender_one", "p", "phone", "send", "exit_1"),
        SenderAccount("s2", "sender_two", "p", "phone", "send", "exit_2"),
    ])
    # 두 계정 모두 첫 발송에서 하드 신호 → 창 안 2개 → 함대 정지
    client = DryRunClient(scripted={
        ("s1", 1): RawSignal(exc_name="ChallengeRequired"),
        ("s2", 1): RawSignal(exc_name="ChallengeRequired"),
    })
    r = _runner(cfg, client, _active_clock(), max_actions=20)
    r.run()
    assert r.fleet_stopped is True


def test_dryrun_control_arm_only_non_dm():
    cfg = _config(senders=[
        SenderAccount("c1", "control_one", "p", "phone", "control", "exit_1"),
    ])
    cfg.params.non_dm_ratio = 1.0
    client = DryRunClient()
    r = _runner(cfg, client, _active_clock(), max_actions=5)
    r.run()
    assert client.sent == []              # 대조군은 DM 안 함
    assert client.non_dm_calls            # 비DM만


def test_dryrun_kill_switch_stops(tmp_path):
    cfg = _config()
    cfg.kill_switch_path = str(tmp_path / "KILL")
    (tmp_path / "KILL").write_text("stop")
    client = DryRunClient()
    r = _runner(cfg, client, _active_clock(), max_actions=5)
    r.run()
    assert client.sent == []              # 킬 스위치가 있으면 아무 발송 안 함
    assert r.fleet_stopped is True


def test_dryrun_rate_limit_caps_sends_within_hour():
    from datetime import datetime, timezone
    cfg = _config()
    cfg.params.max_sends_per_hour = 2
    client = DryRunClient()
    # 10초 간격으로 여러 틱 — 전부 같은 1시간 창 안 → 2건까지만 발송
    clock = FakeClock(datetime(2026, 9, 4, 3, 0, tzinfo=timezone.utc), step_seconds=10.0)
    r = _runner(cfg, client, clock, max_actions=10)
    r.run()
    assert r.ledger.cumulative_sends("s1") == 2  # 상한에서 막힘


def test_dryrun_jitter_sleep_called_after_send():
    calls = []
    cfg = _config()
    cfg.params.jitter_min_seconds = 60
    cfg.params.jitter_max_seconds = 300
    client = DryRunClient()
    r = Runner(
        config=cfg, ledger=Ledger(cfg.ledger_path), client=client,
        clock_dt=_active_clock().now_dt, clock_ts=_active_clock().now_ts,
        advance=lambda: None, sleep=lambda s: calls.append(s),
        rng=random.Random(0), max_actions=1,
    )
    r.run()
    # 발송 후 지터 sleep이 [min,max] 범위 값으로 호출됨
    assert any(60 <= c <= 300 for c in calls), f"지터 sleep 미호출: {calls}"


def test_dryrun_kill_switch_mid_tick_blocks_remaining_accounts(tmp_path):
    cfg = _config(senders=[
        SenderAccount("s1", "sender_one", "p", "phone", "send", "exit_1"),
        SenderAccount("s2", "sender_two", "p", "phone", "send", "exit_2"),
    ])
    kill = tmp_path / "KILL"
    cfg.kill_switch_path = str(kill)

    class KillOnFirstSend(DryRunClient):
        def send_dm(self, a, rcp, t):
            res = super().send_dm(a, rcp, t)
            kill.write_text("stop")  # s1 발송 직후 킬 투입
            return res

    client = KillOnFirstSend()
    r = _runner(cfg, client, _active_clock(), max_actions=10)
    r.run()
    assert len(client.sent) == 1        # s1만 발송, s2는 계정 단위 킬 확인으로 차단
    assert r.fleet_stopped is True


def test_dryrun_repeated_login_required_kills_control():
    cfg = _config(senders=[
        SenderAccount("c1", "control_one", "p", "phone", "control", "exit_1"),
    ])
    cfg.params.non_dm_ratio = 1.0
    client = DryRunClient(non_dm_scripted={
        ("c1", 1): RawSignal(exc_name="LoginRequired"),
        ("c1", 2): RawSignal(exc_name="LoginRequired"),
    })
    r = _runner(cfg, client, _active_clock(), max_actions=10)
    r.run()
    dist = r.ledger.death_cause_distribution()
    assert dist.get("LoginRequired") == 1  # 대조군도 반복 LoginRequired면 死


# --- 09-04 실계정 투입 전 결함 수정분 ---

def _sleep_collecting_runner(cfg, client, max_actions, clock=None):
    calls = []
    clock = clock or _active_clock()
    r = Runner(
        config=cfg, ledger=Ledger(cfg.ledger_path), client=client,
        clock_dt=clock.now_dt, clock_ts=clock.now_ts, advance=clock.advance,
        sleep=lambda s: calls.append(s), rng=random.Random(0), max_actions=max_actions,
    )
    return r, calls


def test_dryrun_non_dm_action_sleeps_jitter():
    # 대조군(비DM 전용)도 액션 뒤 지터 sleep — 무지연 연사면 대조군이 봇 패턴이 된다
    cfg = _config(senders=[
        SenderAccount("c1", "control_one", "p", "phone", "control", "exit_1"),
    ])
    cfg.params.non_dm_ratio = 1.0
    cfg.params.jitter_min_seconds = 60
    cfg.params.jitter_max_seconds = 300
    r, calls = _sleep_collecting_runner(cfg, DryRunClient(), max_actions=1)
    r.run()
    assert any(60 <= c <= 300 for c in calls), f"비DM 뒤 지터 sleep 미호출: {calls}"


def test_dryrun_transient_signal_backs_off():
    # 일시 신호(429)면 설정된 백오프만큼 실제로 대기해야 한다(0초 금지)
    cfg = _config(dummies_n=5)
    cfg.params.transient_backoff_seconds = 600
    client = DryRunClient(scripted={("s1", 1): RawSignal(status_code=429)})
    r, calls = _sleep_collecting_runner(cfg, client, max_actions=1)
    r.run()
    assert 600 in calls, f"백오프 sleep(600) 미호출: {calls}"


def test_dryrun_cold_dm_one_per_dummy_then_observe():
    # 콜드 DM은 사람당 1건 — 더미를 다 돌면 재발송하지 않고 관찰(비DM만) 단계로
    cfg = _config(dummies_n=2)
    cfg.params.non_dm_ratio = 0.5
    client = DryRunClient()
    r = _runner(cfg, client, _active_clock(), max_actions=40)
    r.run()
    recipients = [rcp for _, rcp, _ in client.sent]
    assert len(recipients) == 2
    assert len(set(recipients)) == 2                    # 같은 더미 재발송 없음
    assert client.non_dm_calls, "상한 뒤엔 관찰 단계 비DM만 이어져야 함"


def test_dryrun_observe_window_elapsed_completes_run():
    # 마지막 발송 후 post_send_observe_days가 지나면 계정 관찰 종료 → 발송 계정 전부 끝나면 런 완료
    cfg = _config(dummies_n=1)
    cfg.params.post_send_observe_days = 1
    client = DryRunClient()
    clock = FakeClock(datetime(2026, 9, 4, 3, 0, tzinfo=timezone.utc), step_seconds=86400.0)
    r = _runner(cfg, client, clock, max_actions=50)
    r.run()
    assert r.ledger.cumulative_sends("s1") == 1
    assert r.completed is True


def test_dryrun_observe_phase_is_sparse_by_non_dm_ratio():
    # 관찰 단계는 "켜두고 지켜보기" — non_dm_ratio=0이면 활동 없이 유휴 대기만(좋아요 수백 개 금지)
    cfg = _config(dummies_n=2)
    cfg.params.non_dm_ratio = 0.0
    client = DryRunClient()
    r = _runner(cfg, client, _active_clock(), max_actions=8)
    r.run()
    assert len(client.sent) == 2
    assert client.non_dm_calls == []

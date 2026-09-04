from igdm_harness.signals import RawSignal, SignalGrade
from igdm_harness.detector import classify, is_death, CircuitBreaker


# --- classify ---
def test_ok_when_no_signal():
    assert classify(RawSignal()) is SignalGrade.OK


def test_429_is_transient():
    assert classify(RawSignal(status_code=429)) is SignalGrade.TRANSIENT


def test_please_wait_is_transient():
    assert classify(RawSignal(exc_name="PleaseWaitFewMinutes")) is SignalGrade.TRANSIENT
    assert classify(RawSignal(exc_name="ClientThrottledError")) is SignalGrade.TRANSIENT


def test_server_5xx_is_transient():
    assert classify(RawSignal(status_code=503)) is SignalGrade.TRANSIENT


def test_feedback_required_is_action_block():
    assert classify(RawSignal(exc_name="FeedbackRequired")) is SignalGrade.ACTION_BLOCK


def test_challenge_required_is_terminal():
    assert classify(RawSignal(exc_name="ChallengeRequired")) is SignalGrade.TERMINAL


def test_account_disabled_is_terminal():
    assert classify(RawSignal(exc_name="AccountDisabled")) is SignalGrade.TERMINAL


def test_single_login_required_is_transient():
    # 만료 1회는 재로그인 유도(死 아님)
    assert classify(RawSignal(exc_name="LoginRequired", login_required_streak=1)) is SignalGrade.TRANSIENT


def test_repeated_login_required_is_terminal():
    assert classify(
        RawSignal(exc_name="LoginRequired", login_required_streak=2)
    ) is SignalGrade.TERMINAL


def test_unknown_exception_is_action_block_for_safety():
    # 미상은 안전 우선: 계정 정지(死). 원문은 별도로 기록해 사후 재분류.
    assert classify(RawSignal(exc_name="SomeWeirdError")) is SignalGrade.ACTION_BLOCK


# --- is_death ---
def test_is_death():
    assert is_death(SignalGrade.ACTION_BLOCK) is True
    assert is_death(SignalGrade.TERMINAL) is True
    assert is_death(SignalGrade.TRANSIENT) is False
    assert is_death(SignalGrade.OK) is False


# --- CircuitBreaker ---
def test_breaker_not_tripped_below_threshold():
    cb = CircuitBreaker(window_seconds=900, threshold=2)
    cb.record_hard_signal("acct_a", now=1000.0)
    assert cb.is_tripped(now=1001.0) is False


def test_breaker_trips_on_two_distinct_accounts_in_window():
    cb = CircuitBreaker(window_seconds=900, threshold=2)
    cb.record_hard_signal("acct_a", now=1000.0)
    cb.record_hard_signal("acct_b", now=1100.0)
    assert cb.is_tripped(now=1200.0) is True


def test_breaker_same_account_twice_does_not_trip():
    cb = CircuitBreaker(window_seconds=900, threshold=2)
    cb.record_hard_signal("acct_a", now=1000.0)
    cb.record_hard_signal("acct_a", now=1100.0)
    assert cb.is_tripped(now=1200.0) is False


def test_breaker_window_expiry():
    cb = CircuitBreaker(window_seconds=900, threshold=2)
    cb.record_hard_signal("acct_a", now=1000.0)
    cb.record_hard_signal("acct_b", now=3000.0)  # 2000s 뒤 — 창(900s) 밖
    assert cb.is_tripped(now=3001.0) is False

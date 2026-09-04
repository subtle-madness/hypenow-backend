import random
from datetime import datetime, timezone

from igdm_harness.pacer import (
    is_active_hours,
    jitter_delay_seconds,
    RateLimiter,
    should_insert_non_dm,
)


# --- is_active_hours (KST 10:00~22:00) ---
def test_active_at_kst_noon():
    # 03:00 UTC == 12:00 KST → 활성
    dt = datetime(2026, 9, 4, 3, 0, tzinfo=timezone.utc)
    assert is_active_hours(dt, "Asia/Seoul", 10, 22) is True


def test_inactive_at_kst_predawn():
    # 18:00 UTC == 03:00 KST(익일) → 비활성
    dt = datetime(2026, 9, 4, 18, 0, tzinfo=timezone.utc)
    assert is_active_hours(dt, "Asia/Seoul", 10, 22) is False


def test_boundary_start_inclusive_end_exclusive():
    # 01:00 UTC == 10:00 KST → 활성(시작 포함)
    assert is_active_hours(datetime(2026, 9, 4, 1, 0, tzinfo=timezone.utc), "Asia/Seoul", 10, 22) is True
    # 13:00 UTC == 22:00 KST → 비활성(끝 배타)
    assert is_active_hours(datetime(2026, 9, 4, 13, 0, tzinfo=timezone.utc), "Asia/Seoul", 10, 22) is False


# --- jitter ---
def test_jitter_within_bounds():
    rng = random.Random(42)
    for _ in range(100):
        d = jitter_delay_seconds(rng, 60, 300)
        assert 60 <= d <= 300


def test_jitter_is_deterministic_with_seed():
    assert jitter_delay_seconds(random.Random(1), 60, 300) == jitter_delay_seconds(random.Random(1), 60, 300)


# --- RateLimiter (시간당 상한) ---
def test_rate_limiter_allows_under_cap():
    rl = RateLimiter(max_per_hour=3)
    rl.record(1000.0)
    rl.record(1010.0)
    assert rl.allowed(1020.0) is True  # 2건 < 3


def test_rate_limiter_blocks_at_cap():
    rl = RateLimiter(max_per_hour=3)
    rl.record(1000.0)
    rl.record(1010.0)
    rl.record(1020.0)
    assert rl.allowed(1030.0) is False  # 3건 == 상한


def test_rate_limiter_window_slides():
    rl = RateLimiter(max_per_hour=3)
    rl.record(1000.0)
    rl.record(1010.0)
    rl.record(1020.0)
    # 3700s 뒤(첫 1건은 1시간 밖) → 2건만 유효 → 허용
    assert rl.allowed(1000.0 + 3700) is True


# --- 비DM 삽입 ---
def test_should_insert_non_dm_ratio_bounds():
    rng = random.Random(0)
    assert should_insert_non_dm(rng, 0.0) is False   # 절대 안 함
    assert should_insert_non_dm(random.Random(0), 1.0) is True  # 항상 함


def test_should_insert_non_dm_frequency():
    rng = random.Random(123)
    hits = sum(should_insert_non_dm(rng, 0.3) for _ in range(2000))
    assert 0.2 < hits / 2000 < 0.4  # ~0.3 근방

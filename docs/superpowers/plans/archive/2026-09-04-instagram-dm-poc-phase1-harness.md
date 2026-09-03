# 인스타 DM POC 1단계 하네스 구현 계획

> 상태: ✅ 실행됨 · 2026-09-04 (브랜치 feat/instagram-dm-poc-phase1, 전체 유닛 74 passed·1 skipped)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 신규 인스타 계정이 워밍업 없이 콜드 DM을 자동 발송하면 며칠/몇 건까지 버티는지를, 우리 통제 더미 계정만 상대로 안전하게 실측하는 독립 Python 하네스를 만든다.

**Architecture:** 백엔드(gradle)와 완전 격리된 독립 Python 패키지(`poc/instagram-dm-harness/`). 순수 로직 모듈(config·guard·detector·pacer·device·proxy·ledger)은 TDD로 유닛 테스트하고, instagrapi로 실제 네트워크 I/O를 하는 부분은 `Client` 프로토콜 뒤에 숨겨 `DryRunClient`(실발송 0, 기록만)로 파이프라인 전체를 검증한 뒤 실계정을 투입한다. 안전선(수신자 화이트리스트 코드 차단·3단계 밴 신호 분류·함대 서킷브레이커·킬 스위치)은 필수 기능으로 구현한다.

**Tech Stack:** Python 3.11+, instagrapi(버전 핀 고정), PyYAML(config), sqlite3(stdlib, 원장), pytest(테스트). zoneinfo(stdlib, KST 활성시간).

**정본 설계**: [1단계 하네스 설계](../specs/2026-09-02-instagram-dm-poc-phase1-harness-design.md) · [갈림길 핸드오프](../specs/2026-09-01-instagram-dm-poc-handoff.md)

---

## 파일 구조

```
poc/instagram-dm-harness/
  README.md                      # 목적·안전선·설치·드라이런·실행 순서
  pyproject.toml                 # 패키지 메타 + 의존성 핀(instagrapi 고정)
  .gitignore                     # sessions/·*.db·실제 config·프록시 크리덴셜 무시
  config.example.yaml            # 실행 정의 예시(계정·더미·문구·파라미터) — 크리덴셜 자리표시자
  src/igdm_harness/
    __init__.py                  # 버전
    signals.py                   # SignalGrade enum, RawSignal, 결과 dataclass (instagrapi 미의존)
    detector.py                  # classify(), is_death(), CircuitBreaker
    pacer.py                     # is_active_hours, jitter_delay_seconds, RateLimiter, should_insert_non_dm
    killswitch.py                # kill_requested(path)
    guard.py                     # assert_recipient_allowed (수신자 화이트리스트 코드 차단)
    ledger.py                    # SQLite 스키마 + append + 파생 집계
    config.py                    # dataclass + YAML 로더 + 검증(프록시 1:1·더미·문구)
    device.py                    # L2 한국 기기 프로필 합성(순수)
    proxy.py                     # 계정:출구 1:1 배정 + sticky/geo URL 조립(순수)
    client.py                    # Client 프로토콜 + DryRunClient (instagrapi 미의존)
    instagrapi_client.py         # InstagrapiClient — 실제 instagrapi 래퍼(세션·기기·프록시·DM·도착확인)
    runner.py                    # 오케스트레이션 루프(페이싱·정지·서킷·킬스위치)
    cli.py                       # python -m igdm_harness 진입점(--dry-run 기본)
    __main__.py                  # cli.main() 위임
  tests/
    test_signals.py
    test_detector.py
    test_pacer.py
    test_killswitch.py
    test_guard.py
    test_ledger.py
    test_config.py
    test_device.py
    test_proxy.py
    test_runner_dryrun.py
```

**격리 원칙**: `settings.gradle`·루트 `build.gradle`에 이 디렉토리를 등록하지 않는다(확정결정 #4, 백엔드와 격리). CI(`ci.yml`)도 건드리지 않는다 — 이 하네스는 독립 실행이다.

**모듈 경계 요지**:
- `signals.py`·`detector.py`·`pacer.py`·`guard.py`·`ledger.py`·`config.py`·`device.py`·`proxy.py`·`client.py`(DryRunClient)는 **instagrapi를 import하지 않는다** → 테스트가 instagrapi 설치 없이 돈다.
- `instagrapi_client.py`·`runner.py`(실행 경로)만 무거운 의존을 진다. `runner`는 `Client` 프로토콜에만 의존하므로 DryRunClient로 유닛 테스트 가능.

---

### Task 1: 패키지 스캐폴드

**Files:**
- Create: `poc/instagram-dm-harness/pyproject.toml`
- Create: `poc/instagram-dm-harness/.gitignore`
- Create: `poc/instagram-dm-harness/src/igdm_harness/__init__.py`
- Create: `poc/instagram-dm-harness/tests/__init__.py`
- Create: `poc/instagram-dm-harness/README.md` (스켈레톤 — Task 12에서 완성)

- [ ] **Step 1: pyproject.toml 작성**

```toml
[build-system]
requires = ["setuptools>=68"]
build-backend = "setuptools.build_meta"

[project]
name = "igdm-harness"
version = "0.1.0"
description = "인스타 DM 콜드아웃리치 밴 리스크 실측 POC 하네스 (1단계). 우리 통제 더미 계정 전용."
requires-python = ">=3.11"
# instagrapi는 버전 핀 고정(밴 측정 재현성). 설치 후 실제 버전을 ledger에 런타임 기록한다.
dependencies = [
    "instagrapi==2.1.2",
    "PyYAML>=6.0",
]

[project.optional-dependencies]
dev = ["pytest>=8.0"]

[project.scripts]
igdm-harness = "igdm_harness.cli:main"

[tool.setuptools.packages.find]
where = ["src"]

[tool.pytest.ini_options]
testpaths = ["tests"]
```

- [ ] **Step 2: .gitignore 작성** (크리덴셜·세션·원장 절대 커밋 금지)

```gitignore
# 세션 덤프(로그인 크리덴셜 파생) — 절대 커밋 금지
sessions/
*.session.json
# 원장 DB
*.db
*.sqlite
*.sqlite3
# 실제 실행 config(크리덴셜 포함) — 예시만 커밋
config.yaml
config.*.yaml
!config.example.yaml
# 킬 스위치 파일
KILL
# 파이썬
__pycache__/
*.py[cod]
.venv/
venv/
*.egg-info/
.pytest_cache/
```

- [ ] **Step 3: 패키지 __init__ 작성**

`src/igdm_harness/__init__.py`:
```python
"""인스타 DM 콜드아웃리치 밴 리스크 실측 POC 하네스 (1단계).

안전선: 100% 버릴 테스트 계정 전용. 수신자는 우리 통제 더미 화이트리스트만.
실제 사람 발송은 guard가 코드 레벨에서 원천 차단한다.
"""

__version__ = "0.1.0"
```

`tests/__init__.py`: 빈 파일.

- [ ] **Step 4: README 스켈레톤**

`README.md`:
```markdown
# 인스타 DM POC 1단계 하네스

> ⚠️ 100% 버릴 테스트 계정 전용. 수신자는 우리 통제 더미 화이트리스트만.
> 실제 사람 발송은 코드가 원천 차단한다. 설계: docs/superpowers/specs/2026-09-02-instagram-dm-poc-phase1-harness-design.md

(Task 12에서 완성)
```

- [ ] **Step 5: 설치 확인 및 커밋**

Run: `cd poc/instagram-dm-harness && python -m pip install -e ".[dev]" && python -c "import igdm_harness; print(igdm_harness.__version__)"`
Expected: `0.1.0` 출력(instagrapi 설치가 오래 걸릴 수 있음. 실패 시 최소 `python -c "import sys; sys.path.insert(0,'src'); import igdm_harness"` 로 대체 확인).

```bash
git add poc/instagram-dm-harness
git commit -m "feat(igdm-poc): 하네스 패키지 스캐폴드"
```

---

### Task 2: signals — 신호 등급·결과 타입 (instagrapi 미의존)

**Files:**
- Create: `poc/instagram-dm-harness/src/igdm_harness/signals.py`
- Test: `poc/instagram-dm-harness/tests/test_signals.py`

- [ ] **Step 1: 실패 테스트 작성**

`tests/test_signals.py`:
```python
from igdm_harness.signals import SignalGrade, RawSignal, ActionResult, DeliveryStatus


def test_signal_grades_exist():
    assert SignalGrade.OK.value == "ok"
    assert SignalGrade.TRANSIENT.value == "transient"
    assert SignalGrade.ACTION_BLOCK.value == "action_block"
    assert SignalGrade.TERMINAL.value == "terminal"


def test_raw_signal_defaults():
    s = RawSignal()
    assert s.exc_name is None
    assert s.status_code is None
    assert s.message == ""
    assert s.login_required_streak == 0


def test_action_result_carries_signal_and_raw():
    sig = RawSignal(exc_name="FeedbackRequired", status_code=400)
    r = ActionResult(ok=False, signal=sig, raw_response="{...}")
    assert r.ok is False
    assert r.signal.exc_name == "FeedbackRequired"
    assert r.raw_response == "{...}"


def test_delivery_status_values():
    assert DeliveryStatus.DELIVERED.value == "delivered"
    assert DeliveryStatus.NOT_DELIVERED.value == "not_delivered"
    assert DeliveryStatus.UNKNOWN.value == "unknown"
```

- [ ] **Step 2: 실패 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_signals.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'igdm_harness.signals'`

- [ ] **Step 3: 구현**

`src/igdm_harness/signals.py`:
```python
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
```

- [ ] **Step 4: 통과 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_signals.py -v`
Expected: PASS (4 passed)

- [ ] **Step 5: 커밋**

```bash
git add poc/instagram-dm-harness/src/igdm_harness/signals.py poc/instagram-dm-harness/tests/test_signals.py
git commit -m "feat(igdm-poc): 신호 등급·결과 타입"
```

---

### Task 3: detector — 3단계 분류 + 死 판정 + 함대 서킷브레이커

설계 §6·§7 ⑦. **POC 규칙: 첫 하드 신호(액션차단 또는 종료)를 死로 확정**(복구·재시도 없음). 일시(TRANSIENT)는 死 아님. 미상 예외는 안전 우선으로 ACTION_BLOCK(계정 정지)로 분류하고 원문을 기록해 사후 재분류.

**Files:**
- Create: `poc/instagram-dm-harness/src/igdm_harness/detector.py`
- Test: `poc/instagram-dm-harness/tests/test_detector.py`

- [ ] **Step 1: 실패 테스트 작성**

`tests/test_detector.py`:
```python
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
```

- [ ] **Step 2: 실패 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_detector.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'igdm_harness.detector'`

- [ ] **Step 3: 구현**

`src/igdm_harness/detector.py`:
```python
"""밴 신호 분류·死 판정·함대 서킷브레이커. instagrapi 미의존."""

from __future__ import annotations

from collections import deque
from typing import Deque, Tuple

from .signals import RawSignal, SignalGrade

# 종료(死) 예외 클래스명
_TERMINAL_EXC = {
    "ChallengeRequired",
    "AccountDisabled",
    "UserNotFound",       # 계정 소멸
    "SentryBlock",        # 인스타 차단(강)
}
# 액션차단 예외 클래스명
_ACTION_BLOCK_EXC = {
    "FeedbackRequired",
}
# 일시 예외 클래스명
_TRANSIENT_EXC = {
    "PleaseWaitFewMinutes",
    "ClientThrottledError",
    "RateLimitError",
}

DEFAULT_LOGIN_REQUIRED_DEATH_STREAK = 2


def classify(
    sig: RawSignal,
    login_required_death_streak: int = DEFAULT_LOGIN_REQUIRED_DEATH_STREAK,
) -> SignalGrade:
    """원신호를 3단계로 분류. 미상 예외는 안전 우선으로 ACTION_BLOCK(계정 정지)."""
    exc = sig.exc_name

    if exc == "LoginRequired":
        if sig.login_required_streak >= login_required_death_streak:
            return SignalGrade.TERMINAL
        return SignalGrade.TRANSIENT

    if exc in _TERMINAL_EXC:
        return SignalGrade.TERMINAL
    if exc in _ACTION_BLOCK_EXC:
        return SignalGrade.ACTION_BLOCK
    if exc in _TRANSIENT_EXC:
        return SignalGrade.TRANSIENT

    if sig.status_code == 429:
        return SignalGrade.TRANSIENT
    if sig.status_code is not None and sig.status_code >= 500:
        return SignalGrade.TRANSIENT

    if exc is None:
        return SignalGrade.OK

    # 미상 예외: 계속 두드리지 않도록 계정 정지. 원문은 ledger에 남겨 사후 재분류.
    return SignalGrade.ACTION_BLOCK


def is_death(grade: SignalGrade) -> bool:
    """POC 규칙: 첫 하드 신호(액션차단·종료)를 死로 확정."""
    return grade in (SignalGrade.ACTION_BLOCK, SignalGrade.TERMINAL)


class CircuitBreaker:
    """짧은 창에 서로 다른 N개 계정이 하드 신호 → 함대 전체 정지(계통 문제 방어)."""

    def __init__(self, window_seconds: float = 900.0, threshold: int = 2) -> None:
        self._window = window_seconds
        self._threshold = threshold
        self._events: Deque[Tuple[str, float]] = deque()  # (account_alias, ts)

    def record_hard_signal(self, account_alias: str, now: float) -> None:
        self._events.append((account_alias, now))

    def is_tripped(self, now: float) -> bool:
        cutoff = now - self._window
        # 창 밖 이벤트 제거
        while self._events and self._events[0][1] < cutoff:
            self._events.popleft()
        distinct = {alias for alias, ts in self._events if ts >= cutoff}
        return len(distinct) >= self._threshold
```

- [ ] **Step 4: 통과 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_detector.py -v`
Expected: PASS (전 케이스 passed)

- [ ] **Step 5: 커밋**

```bash
git add poc/instagram-dm-harness/src/igdm_harness/detector.py poc/instagram-dm-harness/tests/test_detector.py
git commit -m "feat(igdm-poc): 밴 신호 3단계 분류·死 판정·서킷브레이커"
```

---

### Task 4: pacer — 활성시간·지터·레이트 상한·비DM 삽입

설계 §5·§6. instagrapi 미의존. 시간·난수를 주입받아 결정적으로 테스트.

**Files:**
- Create: `poc/instagram-dm-harness/src/igdm_harness/pacer.py`
- Test: `poc/instagram-dm-harness/tests/test_pacer.py`

- [ ] **Step 1: 실패 테스트 작성**

`tests/test_pacer.py`:
```python
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
```

- [ ] **Step 2: 실패 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_pacer.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'igdm_harness.pacer'`

- [ ] **Step 3: 구현**

`src/igdm_harness/pacer.py`:
```python
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
```

- [ ] **Step 4: 통과 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_pacer.py -v`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add poc/instagram-dm-harness/src/igdm_harness/pacer.py poc/instagram-dm-harness/tests/test_pacer.py
git commit -m "feat(igdm-poc): 페이싱(활성시간·지터·레이트상한·비DM삽입)"
```

---

### Task 5: killswitch — 사람 킬 스위치

**Files:**
- Create: `poc/instagram-dm-harness/src/igdm_harness/killswitch.py`
- Test: `poc/instagram-dm-harness/tests/test_killswitch.py`

- [ ] **Step 1: 실패 테스트 작성**

`tests/test_killswitch.py`:
```python
from pathlib import Path

from igdm_harness.killswitch import kill_requested


def test_no_kill_when_absent(tmp_path: Path):
    assert kill_requested(tmp_path / "KILL") is False


def test_kill_when_present(tmp_path: Path):
    p = tmp_path / "KILL"
    p.write_text("stop")
    assert kill_requested(p) is True
```

- [ ] **Step 2: 실패 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_killswitch.py -v`
Expected: FAIL — 모듈 없음

- [ ] **Step 3: 구현**

`src/igdm_harness/killswitch.py`:
```python
"""사람 킬 스위치: 지정 경로에 파일이 있으면 함대 전체 즉시 정지."""

from __future__ import annotations

from pathlib import Path


def kill_requested(path: Path) -> bool:
    return Path(path).exists()
```

- [ ] **Step 4: 통과 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_killswitch.py -v`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add poc/instagram-dm-harness/src/igdm_harness/killswitch.py poc/instagram-dm-harness/tests/test_killswitch.py
git commit -m "feat(igdm-poc): 사람 킬 스위치"
```

---

### Task 6: guard — 수신자 화이트리스트 코드 차단 (필수 안전 기능)

설계 §6·§7. **목록에 없는 수신자를 코드 레벨에서 차단**해 실제 사람 오발송을 원천 봉쇄. sender가 매 발송 전 호출한다.

**Files:**
- Create: `poc/instagram-dm-harness/src/igdm_harness/guard.py`
- Test: `poc/instagram-dm-harness/tests/test_guard.py`

- [ ] **Step 1: 실패 테스트 작성**

`tests/test_guard.py`:
```python
import pytest

from igdm_harness.guard import assert_recipient_allowed, RecipientNotAllowedError


def test_allows_recipient_in_allowlist():
    assert_recipient_allowed("dummy_01", {"dummy_01", "dummy_02"})  # 예외 없이 통과


def test_blocks_recipient_not_in_allowlist():
    with pytest.raises(RecipientNotAllowedError):
        assert_recipient_allowed("real_person", {"dummy_01", "dummy_02"})


def test_blocks_on_empty_allowlist():
    with pytest.raises(RecipientNotAllowedError):
        assert_recipient_allowed("dummy_01", set())


def test_case_and_at_sign_normalized():
    # @ 접두·대소문자 차이로 우회되지 않게 정규화
    assert_recipient_allowed("@Dummy_01", {"dummy_01"})


def test_blank_recipient_blocked():
    with pytest.raises(RecipientNotAllowedError):
        assert_recipient_allowed("  ", {"dummy_01"})
```

- [ ] **Step 2: 실패 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_guard.py -v`
Expected: FAIL — 모듈 없음

- [ ] **Step 3: 구현**

`src/igdm_harness/guard.py`:
```python
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
```

- [ ] **Step 4: 통과 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_guard.py -v`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add poc/instagram-dm-harness/src/igdm_harness/guard.py poc/instagram-dm-harness/tests/test_guard.py
git commit -m "feat(igdm-poc): 수신자 화이트리스트 코드 차단 가드"
```

---

### Task 7: ledger — SQLite 원장(표 3개) + 파생 집계

설계 §4·§8. 모든 발송 이벤트를 append(死만 기록하면 곡선·간격 재구성 불가).

**Files:**
- Create: `poc/instagram-dm-harness/src/igdm_harness/ledger.py`
- Test: `poc/instagram-dm-harness/tests/test_ledger.py`

- [ ] **Step 1: 실패 테스트 작성**

`tests/test_ledger.py`:
```python
from igdm_harness.ledger import Ledger, AccountMeta, SendEvent, DeathEvent


def _ledger():
    return Ledger(":memory:")


def test_upsert_and_read_account_meta():
    lg = _ledger()
    lg.upsert_account(AccountMeta(
        account_alias="s1", arm="send", verification="phone",
        proxy_exit="exit_1", device_profile="Galaxy S23/ko_KR",
        instagrapi_version="2.1.2", created_at="2026-09-01T00:00:00Z",
    ))
    metas = lg.list_accounts()
    assert len(metas) == 1
    assert metas[0].arm == "send"
    # 같은 alias 재삽입은 갱신
    lg.upsert_account(AccountMeta(
        account_alias="s1", arm="control", verification="email",
        proxy_exit="exit_1", device_profile="x", instagrapi_version="2.1.2",
        created_at="2026-09-01T00:00:00Z",
    ))
    assert len(lg.list_accounts()) == 1
    assert lg.list_accounts()[0].arm == "control"


def test_record_send_events_and_cumulative():
    lg = _ledger()
    lg.record_send_event(SendEvent(
        ts="2026-09-04T01:00:00Z", account_alias="s1", action="dm_send",
        recipient="dummy_01", result="success", signal="ok",
        delivered="delivered", dt_since_prev=None, message_variant="v1",
    ))
    lg.record_send_event(SendEvent(
        ts="2026-09-04T01:03:00Z", account_alias="s1", action="dm_send",
        recipient="dummy_02", result="success", signal="ok",
        delivered="not_delivered", dt_since_prev=180.0, message_variant="v1",
    ))
    assert lg.cumulative_sends("s1") == 2  # dm_send만 카운트


def test_non_dm_not_counted_as_send():
    lg = _ledger()
    lg.record_send_event(SendEvent(
        ts="t", account_alias="s1", action="non_dm", recipient=None,
        result="success", signal="ok", delivered=None,
        dt_since_prev=None, message_variant=None,
    ))
    assert lg.cumulative_sends("s1") == 0


def test_record_death_and_distribution():
    lg = _ledger()
    lg.record_death(DeathEvent(
        account_alias="s1", died_at="2026-09-04T02:00:00Z",
        signal="ChallengeRequired", cumulative_sends=5,
        survival_seconds=3600.0, nth_send=5, raw_response="{...}",
    ))
    lg.record_death(DeathEvent(
        account_alias="s2", died_at="2026-09-04T02:10:00Z",
        signal="FeedbackRequired", cumulative_sends=3,
        survival_seconds=1800.0, nth_send=3, raw_response="{...}",
    ))
    dist = lg.death_cause_distribution()
    assert dist == {"ChallengeRequired": 1, "FeedbackRequired": 1}


def test_delivery_rate():
    lg = _ledger()
    for delivered in ("delivered", "delivered", "not_delivered", "unknown"):
        lg.record_send_event(SendEvent(
            ts="t", account_alias="s1", action="dm_send", recipient="d",
            result="success", signal="ok", delivered=delivered,
            dt_since_prev=None, message_variant="v1",
        ))
    # 확인된 것 중(unknown 제외) 도착 비율: 2/3
    assert abs(lg.delivery_rate() - (2 / 3)) < 1e-9


def test_survival_rows():
    lg = _ledger()
    lg.record_death(DeathEvent(
        account_alias="s1", died_at="t", signal="ChallengeRequired",
        cumulative_sends=5, survival_seconds=3600.0, nth_send=5, raw_response="",
    ))
    rows = lg.survival_by_account()
    assert rows[0].account_alias == "s1"
    assert rows[0].cumulative_sends == 5
```

- [ ] **Step 2: 실패 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_ledger.py -v`
Expected: FAIL — 모듈 없음

- [ ] **Step 3: 구현**

`src/igdm_harness/ledger.py`:
```python
"""SQLite 원장 — 계정 메타·발송 이벤트·死 이벤트 append + 파생 집계. 설계 §4."""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass
from typing import Dict, List, Optional


@dataclass
class AccountMeta:
    account_alias: str
    arm: str            # send / control
    verification: str   # phone / email
    proxy_exit: str
    device_profile: str
    instagrapi_version: str
    created_at: str


@dataclass
class SendEvent:
    ts: str
    account_alias: str
    action: str                    # dm_send / non_dm
    recipient: Optional[str]
    result: str                    # success / fail
    signal: str                    # 신호 등급·코드
    delivered: Optional[str]       # delivered / not_delivered / unknown / None(비DM)
    dt_since_prev: Optional[float]
    message_variant: Optional[str]


@dataclass
class DeathEvent:
    account_alias: str
    died_at: str
    signal: str
    cumulative_sends: int
    survival_seconds: float
    nth_send: int
    raw_response: str


_SCHEMA = """
CREATE TABLE IF NOT EXISTS account_meta (
    account_alias TEXT PRIMARY KEY,
    arm TEXT NOT NULL,
    verification TEXT,
    proxy_exit TEXT,
    device_profile TEXT,
    instagrapi_version TEXT,
    created_at TEXT
);
CREATE TABLE IF NOT EXISTS send_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts TEXT NOT NULL,
    account_alias TEXT NOT NULL,
    action TEXT NOT NULL,
    recipient TEXT,
    result TEXT NOT NULL,
    signal TEXT,
    delivered TEXT,
    dt_since_prev REAL,
    message_variant TEXT
);
CREATE TABLE IF NOT EXISTS death_event (
    account_alias TEXT PRIMARY KEY,
    died_at TEXT NOT NULL,
    signal TEXT,
    cumulative_sends INTEGER,
    survival_seconds REAL,
    nth_send INTEGER,
    raw_response TEXT
);
"""


class Ledger:
    def __init__(self, db_path: str) -> None:
        self._conn = sqlite3.connect(db_path)
        self._conn.row_factory = sqlite3.Row
        self._conn.executescript(_SCHEMA)
        self._conn.commit()

    def close(self) -> None:
        self._conn.close()

    # --- append ---
    def upsert_account(self, m: AccountMeta) -> None:
        self._conn.execute(
            """INSERT INTO account_meta
               (account_alias, arm, verification, proxy_exit, device_profile,
                instagrapi_version, created_at)
               VALUES (?,?,?,?,?,?,?)
               ON CONFLICT(account_alias) DO UPDATE SET
                 arm=excluded.arm, verification=excluded.verification,
                 proxy_exit=excluded.proxy_exit, device_profile=excluded.device_profile,
                 instagrapi_version=excluded.instagrapi_version,
                 created_at=excluded.created_at""",
            (m.account_alias, m.arm, m.verification, m.proxy_exit,
             m.device_profile, m.instagrapi_version, m.created_at),
        )
        self._conn.commit()

    def record_send_event(self, e: SendEvent) -> None:
        self._conn.execute(
            """INSERT INTO send_event
               (ts, account_alias, action, recipient, result, signal,
                delivered, dt_since_prev, message_variant)
               VALUES (?,?,?,?,?,?,?,?,?)""",
            (e.ts, e.account_alias, e.action, e.recipient, e.result,
             e.signal, e.delivered, e.dt_since_prev, e.message_variant),
        )
        self._conn.commit()

    def record_death(self, e: DeathEvent) -> None:
        self._conn.execute(
            """INSERT INTO death_event
               (account_alias, died_at, signal, cumulative_sends,
                survival_seconds, nth_send, raw_response)
               VALUES (?,?,?,?,?,?,?)
               ON CONFLICT(account_alias) DO NOTHING""",
            (e.account_alias, e.died_at, e.signal, e.cumulative_sends,
             e.survival_seconds, e.nth_send, e.raw_response),
        )
        self._conn.commit()

    # --- read / 파생 집계 ---
    def list_accounts(self) -> List[AccountMeta]:
        rows = self._conn.execute("SELECT * FROM account_meta ORDER BY account_alias").fetchall()
        return [AccountMeta(**dict(r)) for r in rows]

    def cumulative_sends(self, account_alias: str) -> int:
        row = self._conn.execute(
            "SELECT COUNT(*) c FROM send_event WHERE account_alias=? AND action='dm_send'",
            (account_alias,),
        ).fetchone()
        return int(row["c"])

    def death_cause_distribution(self) -> Dict[str, int]:
        rows = self._conn.execute(
            "SELECT signal, COUNT(*) c FROM death_event GROUP BY signal"
        ).fetchall()
        return {r["signal"]: int(r["c"]) for r in rows}

    def delivery_rate(self) -> float:
        """도착 확인된 것(unknown 제외) 중 delivered 비율. 확인분이 없으면 0.0."""
        row = self._conn.execute(
            """SELECT
                 SUM(CASE WHEN delivered='delivered' THEN 1 ELSE 0 END) d,
                 SUM(CASE WHEN delivered IN ('delivered','not_delivered') THEN 1 ELSE 0 END) known
               FROM send_event WHERE action='dm_send'"""
        ).fetchone()
        known = row["known"] or 0
        if known == 0:
            return 0.0
        return (row["d"] or 0) / known

    def survival_by_account(self) -> List[DeathEvent]:
        rows = self._conn.execute(
            "SELECT * FROM death_event ORDER BY account_alias"
        ).fetchall()
        return [DeathEvent(**dict(r)) for r in rows]
```

- [ ] **Step 4: 통과 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_ledger.py -v`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add poc/instagram-dm-harness/src/igdm_harness/ledger.py poc/instagram-dm-harness/tests/test_ledger.py
git commit -m "feat(igdm-poc): SQLite 원장(계정·발송·死) + 파생 집계"
```

---

### Task 8: config — dataclass + YAML 로더 + 검증

설계 §3·§5. 실험의 단일 정본. 프록시 1:1 미중첩·더미 비어있지 않음·문구 존재를 검증.

**Files:**
- Create: `poc/instagram-dm-harness/src/igdm_harness/config.py`
- Create: `poc/instagram-dm-harness/config.example.yaml`
- Test: `poc/instagram-dm-harness/tests/test_config.py`

- [ ] **Step 1: 실패 테스트 작성**

`tests/test_config.py`:
```python
import textwrap
import pytest

from igdm_harness.config import load_config, ConfigError


def _write(tmp_path, body):
    p = tmp_path / "config.yaml"
    p.write_text(textwrap.dedent(body))
    return p


VALID = """
    dry_run: true
    ledger_path: "ledger.db"
    session_dir: "sessions"
    kill_switch_path: "KILL"
    params:
      active_start_hour: 10
      active_end_hour: 22
      timezone: "Asia/Seoul"
      jitter_min_seconds: 60
      jitter_max_seconds: 300
      max_sends_per_hour: 21
      non_dm_ratio: 0.3
      circuit_window_seconds: 900
      circuit_threshold: 2
      login_required_death_streak: 2
      post_send_observe_days: 3
    proxies:
      exit_1: "http://user:pass@resi.example:8000?country=kr&session=a"
      exit_2: "http://user:pass@resi.example:8000?country=kr&session=b"
    senders:
      - alias: s1
        username: sender_one
        password: pw1
        verification: phone
        arm: send
        proxy_exit: exit_1
      - alias: s2
        username: sender_two
        password: pw2
        verification: email
        arm: control
        proxy_exit: exit_2
    dummies:
      - alias: d1
        username: dummy_one
        password: dpw1
      - alias: d2
        username: dummy_two
        password: dpw2
    messages:
      - id: v1
        text: "안녕하세요, 협업 제안 드립니다."
"""


def test_loads_valid_config(tmp_path):
    cfg = load_config(_write(tmp_path, VALID))
    assert cfg.dry_run is True
    assert len(cfg.senders) == 2
    assert cfg.params.max_sends_per_hour == 21
    # 화이트리스트는 더미 username을 정규화해 담는다
    assert "dummy_one" in cfg.allowlist
    assert "dummy_two" in cfg.allowlist


def test_rejects_duplicate_proxy_exit(tmp_path):
    body = VALID.replace("proxy_exit: exit_2", "proxy_exit: exit_1")
    with pytest.raises(ConfigError, match="프록시"):
        load_config(_write(tmp_path, body))


def test_rejects_unknown_proxy_exit(tmp_path):
    body = VALID.replace("proxy_exit: exit_2", "proxy_exit: exit_9")
    with pytest.raises(ConfigError, match="프록시"):
        load_config(_write(tmp_path, body))


def test_rejects_empty_dummies(tmp_path):
    body = VALID.split("dummies:")[0] + "dummies: []\n" + VALID.split("messages:")[1].join(["messages:", ""])
    # 더미 없으면 거부
    body2 = VALID.replace(
        """    dummies:
      - alias: d1
        username: dummy_one
        password: dpw1
      - alias: d2
        username: dummy_two
        password: dpw2
""",
        "    dummies: []\n",
    )
    with pytest.raises(ConfigError, match="더미"):
        load_config(_write(tmp_path, body2))


def test_rejects_empty_messages(tmp_path):
    body = VALID.replace(
        """    messages:
      - id: v1
        text: "안녕하세요, 협업 제안 드립니다."
""",
        "    messages: []\n",
    )
    with pytest.raises(ConfigError, match="문구"):
        load_config(_write(tmp_path, body))


def test_rejects_bad_active_hours(tmp_path):
    body = VALID.replace("active_end_hour: 22", "active_end_hour: 10")
    with pytest.raises(ConfigError, match="활성시간"):
        load_config(_write(tmp_path, body))
```

- [ ] **Step 2: 실패 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_config.py -v`
Expected: FAIL — 모듈 없음

- [ ] **Step 3: 구현**

`src/igdm_harness/config.py`:
```python
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
```

- [ ] **Step 4: config.example.yaml 작성** (크리덴셜 자리표시자)

`config.example.yaml`:
```yaml
# 인스타 DM POC 1단계 실행 정의 예시.
# ⚠️ 실제 크리덴셜을 넣은 파일은 config.yaml 로 저장(.gitignore에서 무시됨).
# ⚠️ 발송 계정·수신 더미는 100% 버릴 테스트 계정만. 운영자가 직접 조달한다.
dry_run: true                 # 실발송 없이 스케줄·기록만. 실계정 투입 전 반드시 true로 검증.
ledger_path: "ledger.db"
session_dir: "sessions"       # 계정당 dump_settings 세션 파일 위치(.gitignore 무시)
kill_switch_path: "KILL"      # 이 파일을 만들면 함대 전체 즉시 정지

params:
  active_start_hour: 10       # KST 활성 시작(포함)
  active_end_hour: 22         # KST 활성 끝(배타)
  timezone: "Asia/Seoul"
  jitter_min_seconds: 60      # 발송 간 최소 간격
  jitter_max_seconds: 300     # 발송 간 최대 간격(1~5분)
  max_sends_per_hour: 21      # 계정당 시간당 상한(피처링 Max 앵커)
  non_dm_ratio: 0.3           # DM 사이 비DM 액션 삽입 확률
  circuit_window_seconds: 900 # 함대 서킷브레이커 창(15분)
  circuit_threshold: 2        # 창 안 하드신호 계정 N개면 전체 정지
  login_required_death_streak: 2  # 연속 LoginRequired N회면 死
  post_send_observe_days: 3   # 발송 종료 후 관찰(지연 밴 포착)

proxies:                      # 한국 sticky 레지던셜, 계정:출구 1:1 미중첩
  exit_1: "http://USER:PASS@resi.vendor.example:8000?country=kr&session=REPLACE_A"
  exit_2: "http://USER:PASS@resi.vendor.example:8000?country=kr&session=REPLACE_B"

senders:                      # 신규 버릴 계정. arm=send(콜드DM) / control(비DM만)
  - alias: s1
    username: REPLACE_sender_1
    password: REPLACE_pw_1
    verification: phone       # phone / email
    arm: send
    proxy_exit: exit_1
  - alias: s2
    username: REPLACE_sender_2
    password: REPLACE_pw_2
    verification: email
    arm: control
    proxy_exit: exit_2

dummies:                      # 우리 통제 수신 더미(받기전용, 프록시 불요). 화이트리스트 정본.
  - alias: d1
    username: REPLACE_dummy_1
    password: REPLACE_dpw_1
  - alias: d2
    username: REPLACE_dummy_2
    password: REPLACE_dpw_2

messages:                     # 콜드 첫 DM 문구 변형(message_variant로 기록)
  - id: v1
    text: "안녕하세요, 브랜드 협업 제안 드리려 연락드립니다."
```

- [ ] **Step 5: 통과 확인 및 커밋**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_config.py -v`
Expected: PASS

```bash
git add poc/instagram-dm-harness/src/igdm_harness/config.py poc/instagram-dm-harness/config.example.yaml poc/instagram-dm-harness/tests/test_config.py
git commit -m "feat(igdm-poc): config 로더·검증(프록시 1:1·더미·문구) + 예시"
```

---

### Task 9: device — L2 한국 기기 프로필 합성 (순수)

설계 §3 ⑤. 계정당 일관된 한국 기기 프로필을 결정적으로 합성. 세션 파일에 영구 고정.

**Files:**
- Create: `poc/instagram-dm-harness/src/igdm_harness/device.py`
- Test: `poc/instagram-dm-harness/tests/test_device.py`

- [ ] **Step 1: 실패 테스트 작성**

`tests/test_device.py`:
```python
import random

from igdm_harness.device import build_device_profile, KOREAN_MODELS


def test_profile_has_required_keys():
    prof = build_device_profile(random.Random(1), app_version="309.0.0.0.0")
    for key in ("device_settings", "uuids", "locale", "country", "user_agent", "app_version"):
        assert key in prof
    ds = prof["device_settings"]
    for key in ("manufacturer", "model", "android_version", "android_release"):
        assert key in ds


def test_locale_is_korean():
    prof = build_device_profile(random.Random(1), app_version="309.0.0.0.0")
    assert prof["locale"] == "ko_KR"
    assert prof["country"] == "KR"


def test_deterministic_with_same_seed():
    a = build_device_profile(random.Random(7), app_version="309.0.0.0.0")
    b = build_device_profile(random.Random(7), app_version="309.0.0.0.0")
    assert a == b


def test_different_seed_differs():
    a = build_device_profile(random.Random(1), app_version="309.0.0.0.0")
    b = build_device_profile(random.Random(2), app_version="309.0.0.0.0")
    assert a["uuids"] != b["uuids"]


def test_model_from_korean_pool():
    prof = build_device_profile(random.Random(3), app_version="309.0.0.0.0")
    assert prof["device_settings"]["model"] in {m["model"] for m in KOREAN_MODELS}
```

- [ ] **Step 2: 실패 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_device.py -v`
Expected: FAIL — 모듈 없음

- [ ] **Step 3: 구현**

`src/igdm_harness/device.py`:
```python
"""L2 한국 실기기 프로필 합성. instagrapi 미의존(순수). 설계 §3 ⑤.

계정당 일관된 프로필 1개를 결정적으로 만든다(같은 rng seed → 같은 프로필).
실제 고정은 세션 파일(dump_settings)에 저장해 영구 재사용한다.
"""

from __future__ import annotations

import random
import uuid
from typing import Dict, List

# 한국에서 흔한 갤럭시 모델 공개 스펙(대표값). 필요 시 최신 스펙으로 갱신.
KOREAN_MODELS: List[Dict[str, str]] = [
    {
        "manufacturer": "samsung", "model": "SM-S911N", "device": "dm1q",
        "cpu": "qcom", "dpi": "480dpi", "resolution": "1080x2340",
        "android_version": "34", "android_release": "14",
    },
    {
        "manufacturer": "samsung", "model": "SM-S916N", "device": "dm2q",
        "cpu": "qcom", "dpi": "480dpi", "resolution": "1080x2340",
        "android_version": "34", "android_release": "14",
    },
    {
        "manufacturer": "samsung", "model": "SM-G991N", "device": "o1q",
        "cpu": "qcom", "dpi": "420dpi", "resolution": "1080x2400",
        "android_version": "33", "android_release": "13",
    },
]

_LOCALE = "ko_KR"
_COUNTRY = "KR"


def _uuid(rng: random.Random) -> str:
    return str(uuid.UUID(int=rng.getrandbits(128)))


def build_device_profile(rng: random.Random, app_version: str) -> Dict:
    model = rng.choice(KOREAN_MODELS)
    device_settings = {
        "app_version": app_version,
        "android_version": model["android_version"],
        "android_release": model["android_release"],
        "dpi": model["dpi"],
        "resolution": model["resolution"],
        "manufacturer": model["manufacturer"],
        "device": model["device"],
        "model": model["model"],
        "cpu": model["cpu"],
        "version_code": "314665256",
    }
    uuids = {
        "phone_id": _uuid(rng),
        "uuid": _uuid(rng),
        "client_session_id": _uuid(rng),
        "advertising_id": _uuid(rng),
        "android_device_id": "android-" + format(rng.getrandbits(64), "016x"),
    }
    user_agent = (
        f"Instagram {app_version} Android "
        f"({model['android_version']}/{model['android_release']}; "
        f"{model['dpi']}; {model['resolution']}; {model['manufacturer']}; "
        f"{model['model']}; {model['device']}; {model['cpu']}; {_LOCALE})"
    )
    return {
        "device_settings": device_settings,
        "uuids": uuids,
        "locale": _LOCALE,
        "country": _COUNTRY,
        "user_agent": user_agent,
        "app_version": app_version,
    }
```

- [ ] **Step 4: 통과 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_device.py -v`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add poc/instagram-dm-harness/src/igdm_harness/device.py poc/instagram-dm-harness/tests/test_device.py
git commit -m "feat(igdm-poc): L2 한국 기기 프로필 합성"
```

---

### Task 10: proxy — 계정:출구 1:1 배정 + URL 조립 (순수)

설계 §3 ④. 발송 계정 → 한국 sticky 레지던셜 출구 1:1. 수신 더미엔 프록시 안 붙임.

**Files:**
- Create: `poc/instagram-dm-harness/src/igdm_harness/proxy.py`
- Test: `poc/instagram-dm-harness/tests/test_proxy.py`

- [ ] **Step 1: 실패 테스트 작성**

`tests/test_proxy.py`:
```python
import pytest

from igdm_harness.proxy import assign_proxies, ProxyAssignmentError
from igdm_harness.config import SenderAccount


def _sender(alias, exit_):
    return SenderAccount(alias=alias, username=alias, password="p",
                         verification="phone", arm="send", proxy_exit=exit_)


def test_assigns_one_to_one():
    senders = [_sender("s1", "exit_1"), _sender("s2", "exit_2")]
    proxies = {"exit_1": "http://a", "exit_2": "http://b"}
    m = assign_proxies(senders, proxies)
    assert m == {"s1": "http://a", "s2": "http://b"}


def test_raises_on_missing_exit():
    senders = [_sender("s1", "exit_9")]
    with pytest.raises(ProxyAssignmentError):
        assign_proxies(senders, {"exit_1": "http://a"})


def test_raises_on_overlap():
    senders = [_sender("s1", "exit_1"), _sender("s2", "exit_1")]
    with pytest.raises(ProxyAssignmentError):
        assign_proxies(senders, {"exit_1": "http://a"})
```

- [ ] **Step 2: 실패 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_proxy.py -v`
Expected: FAIL — 모듈 없음

- [ ] **Step 3: 구현**

`src/igdm_harness/proxy.py`:
```python
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
```

- [ ] **Step 4: 통과 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_proxy.py -v`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add poc/instagram-dm-harness/src/igdm_harness/proxy.py poc/instagram-dm-harness/tests/test_proxy.py
git commit -m "feat(igdm-poc): 계정:프록시 출구 1:1 배정"
```

---

### Task 11: client — Client 프로토콜 + DryRunClient (instagrapi 미의존)

runner가 의존할 추상. DryRunClient는 실발송 0, 호출만 기록 + 스크립트된 신호 주입(死 핸들링 테스트용).

**Files:**
- Create: `poc/instagram-dm-harness/src/igdm_harness/client.py`
- Test: `poc/instagram-dm-harness/tests/test_client_dryrun.py`

- [ ] **Step 1: 실패 테스트 작성**

`tests/test_client_dryrun.py`:
```python
from igdm_harness.client import DryRunClient
from igdm_harness.signals import RawSignal, DeliveryStatus


def test_dryrun_send_records_and_succeeds():
    c = DryRunClient()
    c.ensure_session("s1", {"app_version": "x"}, "http://proxy")
    r = c.send_dm("s1", "dummy_01", "hi")
    assert r.ok is True
    assert c.sent == [("s1", "dummy_01", "hi")]


def test_dryrun_non_dm_records():
    c = DryRunClient()
    r = c.do_non_dm("s1")
    assert r.ok is True
    assert c.non_dm_calls == ["s1"]


def test_dryrun_delivery_defaults_delivered():
    c = DryRunClient()
    assert c.check_delivery("dummy_01", "s1", "hi") is DeliveryStatus.DELIVERED


def test_dryrun_scripted_signal_injection():
    # s1의 2번째 발송에서 ChallengeRequired 주입
    c = DryRunClient(scripted={("s1", 2): RawSignal(exc_name="ChallengeRequired")})
    assert c.send_dm("s1", "d1", "hi").ok is True
    r2 = c.send_dm("s1", "d2", "hi")
    assert r2.ok is False
    assert r2.signal.exc_name == "ChallengeRequired"


def test_dryrun_never_touches_network():
    # 프록시·세션을 주지 않아도 예외 없이 동작(네트워크 미접촉 보장)
    c = DryRunClient()
    assert c.send_dm("s1", "d1", "hi").ok is True
```

- [ ] **Step 2: 실패 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_client_dryrun.py -v`
Expected: FAIL — 모듈 없음

- [ ] **Step 3: 구현**

`src/igdm_harness/client.py`:
```python
"""Client 프로토콜 + DryRunClient. instagrapi 미의존 → runner를 네트워크 없이 테스트.

실제 네트워크 구현은 instagrapi_client.InstagrapiClient (별 모듈, instagrapi 의존).
"""

from __future__ import annotations

from typing import Dict, Optional, Protocol, Tuple

from .signals import ActionResult, DeliveryStatus, RawSignal


class Client(Protocol):
    def ensure_session(self, account_alias: str, device_profile: Dict, proxy_url: Optional[str]) -> None:
        """세션 로드 또는 계정당 1회 로그인 후 dump_settings. instagrapi 담당."""
        ...

    def send_dm(self, account_alias: str, recipient_username: str, text: str) -> ActionResult:
        ...

    def do_non_dm(self, account_alias: str) -> ActionResult:
        """비DM 활동(피드 열람·좋아요 소수)."""
        ...

    def check_delivery(self, dummy_username: str, from_username: str, text: str) -> DeliveryStatus:
        """수신 더미에 로그인해 도착/사일런트드롭 확인."""
        ...


class DryRunClient:
    """실발송 0. 호출만 기록. scripted로 특정 (account, nth_send)에 신호 주입."""

    def __init__(self, scripted: Optional[Dict[Tuple[str, int], RawSignal]] = None) -> None:
        self.scripted = scripted or {}
        self.sent: list[Tuple[str, str, str]] = []
        self.non_dm_calls: list[str] = []
        self.sessions: list[str] = []
        self._send_counts: Dict[str, int] = {}

    def ensure_session(self, account_alias: str, device_profile: Dict, proxy_url: Optional[str]) -> None:
        self.sessions.append(account_alias)

    def send_dm(self, account_alias: str, recipient_username: str, text: str) -> ActionResult:
        n = self._send_counts.get(account_alias, 0) + 1
        self._send_counts[account_alias] = n
        self.sent.append((account_alias, recipient_username, text))
        sig = self.scripted.get((account_alias, n))
        if sig is not None:
            return ActionResult(ok=False, signal=sig, raw_response=f"dryrun-scripted:{sig.exc_name}")
        return ActionResult(ok=True, signal=None, raw_response="dryrun-ok")

    def do_non_dm(self, account_alias: str) -> ActionResult:
        self.non_dm_calls.append(account_alias)
        return ActionResult(ok=True, signal=None, raw_response="dryrun-nondm")

    def check_delivery(self, dummy_username: str, from_username: str, text: str) -> DeliveryStatus:
        return DeliveryStatus.DELIVERED
```

- [ ] **Step 4: 통과 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_client_dryrun.py -v`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add poc/instagram-dm-harness/src/igdm_harness/client.py poc/instagram-dm-harness/tests/test_client_dryrun.py
git commit -m "feat(igdm-poc): Client 프로토콜 + DryRunClient"
```

---

### Task 12: runner — 오케스트레이션 루프 + 드라이런 파이프라인 검증

설계 §3. pacer·detector·guard·ledger·client·killswitch를 엮는다. 시간·난수·sleep·client를 주입받아 결정적으로 테스트. **드라이런 파이프라인이 이 하네스의 첫 동작하는 슬라이스**.

**Files:**
- Create: `poc/instagram-dm-harness/src/igdm_harness/runner.py`
- Test: `poc/instagram-dm-harness/tests/test_runner_dryrun.py`

- [ ] **Step 1: 실패 테스트 작성**

`tests/test_runner_dryrun.py`:
```python
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
    cfg = _config()
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
```

- [ ] **Step 2: 실패 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_runner_dryrun.py -v`
Expected: FAIL — 모듈 없음

- [ ] **Step 3: 구현**

`src/igdm_harness/runner.py`:
```python
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
        while actions < self.max_actions:
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
            if not progressed:
                # 아무도 진행 못 함(전부 비활성 등) — 무한루프 방지로 시간만 전진
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
```

- [ ] **Step 4: 통과 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_runner_dryrun.py -v`
Expected: PASS (전 케이스). 실패 시 death/transient/circuit 분기를 테스트 기대와 맞춰 조정.

- [ ] **Step 5: 커밋**

```bash
git add poc/instagram-dm-harness/src/igdm_harness/runner.py poc/instagram-dm-harness/tests/test_runner_dryrun.py
git commit -m "feat(igdm-poc): runner 오케스트레이션 + 드라이런 파이프라인 검증"
```

---

### Task 13: instagrapi_client — 실제 instagrapi 래퍼 (실계정 경로)

설계 §3 ②③④⑤⑦. 실제 네트워크 I/O. 유닛 테스트 대상 아님(라이브 계정 필요) — import 가능성과 수동 스모크만. `Client` 프로토콜을 만족.

**Files:**
- Create: `poc/instagram-dm-harness/src/igdm_harness/instagrapi_client.py`
- Test: `poc/instagram-dm-harness/tests/test_instagrapi_client_import.py` (구조 확인만)

- [ ] **Step 1: 구조 확인 테스트 작성**

`tests/test_instagrapi_client_import.py`:
```python
import importlib.util
import pytest

# instagrapi 미설치 환경에서도 테스트 수집이 깨지지 않게 skip 처리
instagrapi_available = importlib.util.find_spec("instagrapi") is not None


@pytest.mark.skipif(not instagrapi_available, reason="instagrapi 미설치")
def test_instagrapi_client_satisfies_protocol():
    from igdm_harness.instagrapi_client import InstagrapiClient
    for m in ("ensure_session", "send_dm", "do_non_dm", "check_delivery"):
        assert hasattr(InstagrapiClient, m)
```

- [ ] **Step 2: 실패 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_instagrapi_client_import.py -v`
Expected: instagrapi 설치 시 FAIL(모듈 없음), 미설치 시 SKIP

- [ ] **Step 3: 구현**

`src/igdm_harness/instagrapi_client.py`:
```python
"""실제 instagrapi 래퍼. 세션(계정당 1회 로그인·dump_settings)·기기·프록시·DM·도착확인.

⚠️ 실계정 네트워크 I/O. 자동 재시도·복구 없음(하드 신호는 runner가 死로 처리).
⚠️ 실행 전 반드시 드라이런(DryRunClient)으로 파이프라인 검증.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, Optional

from instagrapi import Client as IGClient
from instagrapi.exceptions import (
    ChallengeRequired, FeedbackRequired, LoginRequired, PleaseWaitFewMinutes,
    ClientError,
)

from .signals import ActionResult, DeliveryStatus, RawSignal


class InstagrapiClient:
    """Client 프로토콜 구현. 계정당 IGClient 인스턴스를 세션 디렉토리에 영구화."""

    def __init__(self, session_dir: str, device_profiles: Dict[str, Dict]) -> None:
        self._session_dir = Path(session_dir)
        self._session_dir.mkdir(parents=True, exist_ok=True)
        self._device_profiles = device_profiles      # {alias: build_device_profile(...)}
        self._clients: Dict[str, IGClient] = {}
        self._creds: Dict[str, tuple] = {}            # {alias: (username, password)}
        self._dummy_clients: Dict[str, IGClient] = {}

    def register_credentials(self, alias: str, username: str, password: str) -> None:
        self._creds[alias] = (username, password)

    def register_dummy(self, username: str, password: str) -> None:
        cl = IGClient()
        cl.login(username, password)
        self._dummy_clients[username] = cl

    def _session_path(self, alias: str) -> Path:
        return self._session_dir / f"{alias}.session.json"

    def ensure_session(self, account_alias: str, device_profile: Dict, proxy_url: Optional[str]) -> None:
        cl = IGClient()
        if proxy_url:
            cl.set_proxy(proxy_url)
        prof = self._device_profiles.get(account_alias) or device_profile
        if prof:
            cl.set_settings({
                "device_settings": prof.get("device_settings", {}),
                "uuids": prof.get("uuids", {}),
                "locale": prof.get("locale", "ko_KR"),
                "country": prof.get("country", "KR"),
                "user_agent": prof.get("user_agent", ""),
            })
        path = self._session_path(account_alias)
        username, password = self._creds[account_alias]
        if path.exists():
            cl.load_settings(path)
            cl.login(username, password)   # 세션 있으면 재검증만, 없으면 정식 로그인
        else:
            cl.login(username, password)   # 계정당 1회 정식 로그인
            cl.dump_settings(path)
        self._clients[account_alias] = cl

    def send_dm(self, account_alias: str, recipient_username: str, text: str) -> ActionResult:
        cl = self._clients[account_alias]
        try:
            uid = cl.user_id_from_username(recipient_username)
            cl.direct_send(text, user_ids=[uid])
            return ActionResult(ok=True, signal=None, raw_response="sent")
        except Exception as exc:  # noqa: BLE001 — 신호는 detector가 분류
            return ActionResult(ok=False, signal=self._to_signal(exc),
                                raw_response=str(exc))

    def do_non_dm(self, account_alias: str) -> ActionResult:
        cl = self._clients[account_alias]
        try:
            medias = cl.explore_medias(amount=3)   # 피드 열람(가벼운 비DM 활동)
            for m in medias[:1]:
                cl.media_like(m.id)                # 소수 좋아요
            return ActionResult(ok=True, signal=None, raw_response="non_dm ok")
        except Exception as exc:  # noqa: BLE001
            return ActionResult(ok=False, signal=self._to_signal(exc),
                                raw_response=str(exc))

    def check_delivery(self, dummy_username: str, from_username: str, text: str) -> DeliveryStatus:
        cl = self._dummy_clients.get(dummy_username)
        if cl is None:
            return DeliveryStatus.UNKNOWN
        try:
            # 받은함 + 요청함(pending) 스레드를 훑어 from_username에서 온 text 존재 확인
            threads = cl.direct_threads(amount=20) + cl.direct_pending_inbox(amount=20)
            for t in threads:
                senders = {u.username for u in t.users}
                if from_username in senders:
                    for msg in getattr(t, "messages", []) or []:
                        if getattr(msg, "text", None) == text:
                            return DeliveryStatus.DELIVERED
            return DeliveryStatus.NOT_DELIVERED
        except Exception:  # noqa: BLE001
            return DeliveryStatus.UNKNOWN

    @staticmethod
    def _to_signal(exc: Exception) -> RawSignal:
        status = None
        response = getattr(exc, "response", None)
        if response is not None:
            status = getattr(response, "status_code", None)
        return RawSignal(exc_name=type(exc).__name__, status_code=status, message=str(exc))
```

- [ ] **Step 4: 통과 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_instagrapi_client_import.py -v`
Expected: instagrapi 설치 시 PASS, 미설치 시 SKIP. (API 시그니처가 핀 버전과 다르면 `send_dm`/`check_delivery` 호출부를 실제 버전 문서에 맞춰 조정.)

- [ ] **Step 5: 커밋**

```bash
git add poc/instagram-dm-harness/src/igdm_harness/instagrapi_client.py poc/instagram-dm-harness/tests/test_instagrapi_client_import.py
git commit -m "feat(igdm-poc): instagrapi 실계정 래퍼(세션·기기·프록시·DM·도착확인)"
```

---

### Task 14: cli — 진입점 + README 완성

**Files:**
- Create: `poc/instagram-dm-harness/src/igdm_harness/cli.py`
- Create: `poc/instagram-dm-harness/src/igdm_harness/__main__.py`
- Modify: `poc/instagram-dm-harness/README.md`
- Test: `poc/instagram-dm-harness/tests/test_cli_dryrun.py`

- [ ] **Step 1: 실패 테스트 작성**

`tests/test_cli_dryrun.py`:
```python
import textwrap
from igdm_harness.cli import build_runner_from_config


def test_cli_builds_dryrun_runner(tmp_path):
    cfg = tmp_path / "config.yaml"
    cfg.write_text(textwrap.dedent("""
        dry_run: true
        ledger_path: ":memory:"
        session_dir: "sessions"
        kill_switch_path: "KILL"
        params: {}
        proxies:
          exit_1: "http://a"
        senders:
          - alias: s1
            username: sender_one
            password: pw
            verification: phone
            arm: send
            proxy_exit: exit_1
        dummies:
          - alias: d1
            username: dummy_one
            password: dpw
        messages:
          - id: v1
            text: "hi"
    """))
    runner = build_runner_from_config(str(cfg), max_actions=3)
    # 드라이런이면 DryRunClient가 배선됐는지 확인
    from igdm_harness.client import DryRunClient
    assert isinstance(runner.client, DryRunClient)
    runner.run()  # 예외 없이 완주
    assert runner.ledger.cumulative_sends("s1") >= 0
```

- [ ] **Step 2: 실패 확인**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_cli_dryrun.py -v`
Expected: FAIL — 모듈 없음

- [ ] **Step 3: 구현**

`src/igdm_harness/cli.py`:
```python
"""CLI 진입점. 드라이런 기본. 실발송은 --live 명시 + 확인 문자열 필요."""

from __future__ import annotations

import argparse
import random
import sys
import time
from datetime import datetime, timezone

from .client import DryRunClient
from .config import load_config
from .device import build_device_profile
from .ledger import Ledger
from .runner import Runner

_IG_APP_VERSION = "309.0.0.0.0"  # 핀 버전에 맞춰 갱신


def build_runner_from_config(config_path: str, *, force_live: bool = False, max_actions: int = 10_000) -> Runner:
    cfg = load_config(config_path)
    ledger = Ledger(cfg.ledger_path)

    live = force_live and not cfg.dry_run
    if live:
        from .instagrapi_client import InstagrapiClient
        import instagrapi
        rng = random.Random()
        device_profiles = {
            s.alias: build_device_profile(random.Random(f"{s.alias}".__hash__()), _IG_APP_VERSION)
            for s in cfg.senders
        }
        client = InstagrapiClient(cfg.session_dir, device_profiles)
        for s in cfg.senders:
            client.register_credentials(s.alias, s.username, s.password)
        for d in cfg.dummies:
            client.register_dummy(d.username, d.password)
        ig_version = getattr(instagrapi, "__version__", "unknown")
    else:
        rng = random.Random(0)
        client = DryRunClient()
        ig_version = "dryrun"

    # 계정 메타 기록
    from .ledger import AccountMeta
    for s in cfg.senders:
        prof = "dryrun" if not live else str(device_profiles[s.alias]["device_settings"].get("model"))
        ledger.upsert_account(AccountMeta(
            account_alias=s.alias, arm=s.arm, verification=s.verification,
            proxy_exit=s.proxy_exit, device_profile=prof,
            instagrapi_version=ig_version, created_at=_now_iso(),
        ))

    return Runner(
        config=cfg, ledger=ledger, client=client,
        clock_dt=lambda: datetime.now(timezone.utc),
        clock_ts=lambda: time.time(),
        advance=lambda: None,
        sleep=time.sleep,
        rng=rng, max_actions=max_actions,
    )


def _now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="인스타 DM POC 1단계 하네스")
    ap.add_argument("--config", required=True, help="실행 config YAML 경로")
    ap.add_argument("--live", action="store_true",
                    help="실발송 활성(config.dry_run=false 여야 함). 미지정 시 드라이런.")
    ap.add_argument("--confirm-live", default="",
                    help="실발송 시 안전 확인 문자열 'I-UNDERSTAND-BURNER-ONLY' 필요")
    ap.add_argument("--max-actions", type=int, default=10_000)
    args = ap.parse_args(argv)

    if args.live and args.confirm_live != "I-UNDERSTAND-BURNER-ONLY":
        print("실발송(--live)은 --confirm-live I-UNDERSTAND-BURNER-ONLY 가 필요합니다.", file=sys.stderr)
        print("100% 버릴 테스트 계정·우리 통제 더미만. 실계정·실사람 금지.", file=sys.stderr)
        return 2

    runner = build_runner_from_config(args.config, force_live=args.live, max_actions=args.max_actions)
    mode = "LIVE" if (args.live and not runner.config.dry_run) else "DRY-RUN"
    print(f"[{mode}] 하네스 시작 — 계정 {len(runner.config.senders)} · 더미 {len(runner.config.dummies)}")
    runner.run()
    print(f"[{mode}] 종료. fleet_stopped={runner.fleet_stopped}")
    print(f"死 분포: {runner.ledger.death_cause_distribution()}")
    return 0
```

`src/igdm_harness/__main__.py`:
```python
import sys
from .cli import main

if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 4: 통과 확인 + 드라이런 스모크**

Run: `cd poc/instagram-dm-harness && python -m pytest tests/test_cli_dryrun.py -v`
Expected: PASS

Run(스모크): `cd poc/instagram-dm-harness && cp config.example.yaml config.smoke.yaml && python -m igdm_harness --config config.smoke.yaml --max-actions 5 && rm -f config.smoke.yaml ledger.db`
Expected: `[DRY-RUN] 하네스 시작 ... 종료` 출력, 예외 없음. (config.smoke.yaml은 .gitignore의 `config.*.yaml`에 걸려 커밋 안 됨.)

- [ ] **Step 5: README 완성**

`README.md` 전체를 아래로 교체:
````markdown
# 인스타 DM POC 1단계 하네스

> ⚠️ **안전선(협상 불가)**: 100% 버릴 테스트 계정 전용. 수신자는 우리 통제 더미 화이트리스트만.
> 목록에 없는 수신자는 코드가 원천 차단한다(`guard.assert_recipient_allowed`). 실제 사람 발송 금지.

신규 인스타 계정이 워밍업 없이 콜드 DM을 자동 발송하면 며칠/몇 건까지 버티는지를 실측하는 독립 하네스.
설계 정본: [1단계 하네스 설계](../../docs/superpowers/specs/2026-09-02-instagram-dm-poc-phase1-harness-design.md),
[갈림길 핸드오프](../../docs/superpowers/specs/2026-09-01-instagram-dm-poc-handoff.md).

## 안전 기능
- **수신자 화이트리스트 코드 차단**: 발송 전 매번 더미 목록 대조, 미포함이면 예외로 차단.
- **밴 신호 3단계 분류**: 일시(429/PleaseWait=백오프)·액션차단(FeedbackRequired)·종료(ChallengeRequired 등). 하드 신호는 즉시 계정 死·정지, 재시도·복구 없음.
- **함대 서킷브레이커**: 짧은 창(기본 15분)에 2개 이상 계정이 하드 신호면 전체 정지.
- **사람 킬 스위치**: `KILL` 파일을 만들면 함대 즉시 정지.
- **드라이런 기본**: 실발송 없이 스케줄·기록만. 실계정 투입 전 필수 검증.

## 설치
```bash
cd poc/instagram-dm-harness
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
```
instagrapi는 `pyproject.toml`에 버전 핀 고정(밴 측정 재현성). 실제 설치 버전은 실행 시 원장에 기록된다.

## 테스트
```bash
python -m pytest -v
```
순수 로직 모듈(detector·pacer·ledger·config·guard·device·proxy·runner)은 instagrapi 없이 돈다.

## 드라이런(실발송 0)
```bash
cp config.example.yaml config.yaml   # 크리덴셜 채우기(.gitignore로 무시됨)
python -m igdm_harness --config config.yaml --max-actions 20
```
DryRunClient가 배선돼 네트워크에 접촉하지 않고 스케줄·원장 기록만 검증한다.

## 실발송(버릴 계정만)
`config.yaml`에서 `dry_run: false`로 두고:
```bash
python -m igdm_harness --config config.yaml --live --confirm-live I-UNDERSTAND-BURNER-ONLY
```
확인 문자열 없이는 실발송이 시작되지 않는다.

## 계정 조달(스코프 밖)
발송 계정·수신 더미 20개는 **운영자가 직접 준비**한다. 하네스는 config의 로그인 목록만 받는다(출처 무관).
계정 생성·구매 로직은 이 하네스에 없다.

## 원장 읽기
SQLite `ledger_path`에 계정 메타·발송 이벤트·死 이벤트 3표. 파생: 생존곡선(계정별 死 시점·누적발송), 死 사유 분포, 도착률.
```bash
sqlite3 ledger.db "SELECT account_alias, action, result, signal, delivered FROM send_event ORDER BY id;"
sqlite3 ledger.db "SELECT * FROM death_event;"
```
````

- [ ] **Step 6: 커밋**

```bash
git add poc/instagram-dm-harness/src/igdm_harness/cli.py poc/instagram-dm-harness/src/igdm_harness/__main__.py poc/instagram-dm-harness/README.md poc/instagram-dm-harness/tests/test_cli_dryrun.py
git commit -m "feat(igdm-poc): CLI 진입점(드라이런 기본·실발송 확인가드) + README"
```

---

### Task 15: 전체 테스트 통과 + 계획 문서 아카이브

- [ ] **Step 1: 전체 유닛 테스트**

Run: `cd poc/instagram-dm-harness && python -m pytest -v`
Expected: 전 케이스 PASS(instagrapi 미설치면 test_instagrapi_client_import는 SKIP).

- [ ] **Step 2: 설계 문서 상태 갱신**

`docs/superpowers/specs/2026-09-02-instagram-dm-poc-phase1-harness-design.md` 상태 헤더를 `🟢 활성 · 설계(구현 착수 전)` → `🟢 활성 · ✅ 1단계 하네스 구현됨`으로. (스펙은 영구 보존이므로 이동하지 않고 상태만 갱신.)

- [ ] **Step 3: 계획 문서 아카이브**

```bash
mkdir -p docs/superpowers/plans/archive
git mv docs/superpowers/plans/2026-09-04-instagram-dm-poc-phase1-harness.md docs/superpowers/plans/archive/
```
이 계획 파일 상단 상태 헤더를 `✅ 실행됨`으로 갱신.

- [ ] **Step 4: 커밋**

```bash
git add -A
git commit -m "docs(igdm-poc): 1단계 하네스 구현 완료 — 설계 상태 갱신·계획 아카이브"
```

- [ ] **Step 5: 보고 및 PR 여부 확인**

push·PR은 사용자 명시 승인 후에만(전역 규칙). 구현 완료·전체 테스트 결과를 보고하고 push/PR 여부를 묻는다.

---

## 자기 검토 결과

**스펙 커버리지**(설계 §3 모듈 → 태스크):
- config→T8, session→T13(ensure_session), device→T9, proxy→T10, sender→T13(send_dm)+guard T6, pacer→T4, detector→T3, delivery→T13(check_delivery), ledger→T7, runner→T12. 안전(§6·§7): guard T6·detector 3단계+서킷 T3·킬스위치 T5·드라이런 T11+T12·실발송 확인가드 T14. 기록(§4) 3표+파생 T7. 파라미터(§5) config T8+example. ✅ 전 항목 태스크 있음.

**자리표시자 스캔**: 없음(전 스텝 실제 코드·명령).

**타입 일관성**: `RawSignal`/`ActionResult`/`SignalGrade`/`DeliveryStatus`(T2) → detector(T3)·client(T11)·runner(T12)에서 동일 시그니처 사용. `SendEvent`/`DeathEvent`/`AccountMeta`(T7) → runner·cli에서 동일 필드. `Client` 프로토콜 4메서드(T11) → DryRunClient·InstagrapiClient 동일. `build_allowlist`/`assert_recipient_allowed`(T6) → config·runner에서 사용. `SenderAccount`(T8) → proxy(T10)·runner에서 사용. ✅ 일관.

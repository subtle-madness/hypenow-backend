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
            # str seed는 random.Random이 안정적으로 처리(PYTHONHASHSEED에 좌우되는
            # str.__hash__()와 달리 재현성이 보장됨).
            s.alias: build_device_profile(random.Random(s.alias), _IG_APP_VERSION)
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

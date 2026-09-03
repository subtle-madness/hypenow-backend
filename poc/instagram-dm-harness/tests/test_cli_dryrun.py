import textwrap
import time

from igdm_harness.cli import build_runner_from_config


def test_cli_builds_dryrun_runner(tmp_path, monkeypatch):
    # cli의 실행 배선은 실시계·실sleep(time.sleep)을 쓴다 — 러너가 이제 지터·유휴 대기를
    # 실제로 호출하므로(레이트·지터 강제 수정분), 그대로 두면 이 배선 스모크 테스트가
    # 실제 벽시계로 수십~수백 초 대기한다. 여기선 배선 자체만 검증하면 되므로
    # time.sleep을 no-op으로 바꿔 결정적·즉시 종료시킨다.
    monkeypatch.setattr(time, "sleep", lambda seconds: None)
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

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

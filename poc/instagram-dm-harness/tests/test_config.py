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

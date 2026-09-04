from pathlib import Path

from igdm_harness.killswitch import kill_requested


def test_no_kill_when_absent(tmp_path: Path):
    assert kill_requested(tmp_path / "KILL") is False


def test_kill_when_present(tmp_path: Path):
    p = tmp_path / "KILL"
    p.write_text("stop")
    assert kill_requested(p) is True

"""사람 킬 스위치: 지정 경로에 파일이 있으면 함대 전체 즉시 정지."""

from __future__ import annotations

from pathlib import Path


def kill_requested(path: Path) -> bool:
    return Path(path).exists()

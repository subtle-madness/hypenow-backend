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

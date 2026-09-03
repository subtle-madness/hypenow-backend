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

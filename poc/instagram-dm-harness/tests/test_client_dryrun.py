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

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

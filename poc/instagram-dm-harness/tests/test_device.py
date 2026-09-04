import random

from igdm_harness.device import build_device_profile, KOREAN_MODELS


def test_profile_has_required_keys():
    prof = build_device_profile(random.Random(1), app_version="309.0.0.0.0")
    for key in ("device_settings", "uuids", "locale", "country", "user_agent", "app_version"):
        assert key in prof
    ds = prof["device_settings"]
    for key in ("manufacturer", "model", "android_version", "android_release"):
        assert key in ds


def test_locale_is_korean():
    prof = build_device_profile(random.Random(1), app_version="309.0.0.0.0")
    assert prof["locale"] == "ko_KR"
    assert prof["country"] == "KR"


def test_deterministic_with_same_seed():
    a = build_device_profile(random.Random(7), app_version="309.0.0.0.0")
    b = build_device_profile(random.Random(7), app_version="309.0.0.0.0")
    assert a == b


def test_different_seed_differs():
    a = build_device_profile(random.Random(1), app_version="309.0.0.0.0")
    b = build_device_profile(random.Random(2), app_version="309.0.0.0.0")
    assert a["uuids"] != b["uuids"]


def test_model_from_korean_pool():
    prof = build_device_profile(random.Random(3), app_version="309.0.0.0.0")
    assert prof["device_settings"]["model"] in {m["model"] for m in KOREAN_MODELS}

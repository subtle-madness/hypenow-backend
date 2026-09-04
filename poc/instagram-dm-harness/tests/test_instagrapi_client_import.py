import importlib.util
import pytest

# instagrapi 미설치 환경에서도 테스트 수집이 깨지지 않게 skip 처리
instagrapi_available = importlib.util.find_spec("instagrapi") is not None


@pytest.mark.skipif(not instagrapi_available, reason="instagrapi 미설치")
def test_instagrapi_client_satisfies_protocol():
    from igdm_harness.instagrapi_client import InstagrapiClient
    for m in ("ensure_session", "send_dm", "do_non_dm", "check_delivery"):
        assert hasattr(InstagrapiClient, m)

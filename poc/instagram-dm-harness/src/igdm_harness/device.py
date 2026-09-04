"""L2 한국 실기기 프로필 합성. instagrapi 미의존(순수). 설계 §3 ⑤.

계정당 일관된 프로필 1개를 결정적으로 만든다(같은 rng seed → 같은 프로필).
실제 고정은 세션 파일(dump_settings)에 저장해 영구 재사용한다.
"""

from __future__ import annotations

import random
import uuid
from typing import Dict, List

# 한국에서 흔한 갤럭시 모델 공개 스펙(대표값). 필요 시 최신 스펙으로 갱신.
KOREAN_MODELS: List[Dict[str, str]] = [
    {
        "manufacturer": "samsung", "model": "SM-S911N", "device": "dm1q",
        "cpu": "qcom", "dpi": "480dpi", "resolution": "1080x2340",
        "android_version": "34", "android_release": "14",
    },
    {
        "manufacturer": "samsung", "model": "SM-S916N", "device": "dm2q",
        "cpu": "qcom", "dpi": "480dpi", "resolution": "1080x2340",
        "android_version": "34", "android_release": "14",
    },
    {
        "manufacturer": "samsung", "model": "SM-G991N", "device": "o1q",
        "cpu": "qcom", "dpi": "420dpi", "resolution": "1080x2400",
        "android_version": "33", "android_release": "13",
    },
]

_LOCALE = "ko_KR"
_COUNTRY = "KR"


def _uuid(rng: random.Random) -> str:
    return str(uuid.UUID(int=rng.getrandbits(128)))


def build_device_profile(rng: random.Random, app_version: str) -> Dict:
    model = rng.choice(KOREAN_MODELS)
    device_settings = {
        "app_version": app_version,
        "android_version": model["android_version"],
        "android_release": model["android_release"],
        "dpi": model["dpi"],
        "resolution": model["resolution"],
        "manufacturer": model["manufacturer"],
        "device": model["device"],
        "model": model["model"],
        "cpu": model["cpu"],
        "version_code": "314665256",
    }
    uuids = {
        "phone_id": _uuid(rng),
        "uuid": _uuid(rng),
        "client_session_id": _uuid(rng),
        "advertising_id": _uuid(rng),
        "android_device_id": "android-" + format(rng.getrandbits(64), "016x"),
    }
    user_agent = (
        f"Instagram {app_version} Android "
        f"({model['android_version']}/{model['android_release']}; "
        f"{model['dpi']}; {model['resolution']}; {model['manufacturer']}; "
        f"{model['model']}; {model['device']}; {model['cpu']}; {_LOCALE})"
    )
    return {
        "device_settings": device_settings,
        "uuids": uuids,
        "locale": _LOCALE,
        "country": _COUNTRY,
        "user_agent": user_agent,
        "app_version": app_version,
    }

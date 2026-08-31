package com.celfit.monitoring.hiker;

import com.celfit.monitoring.store.AppSettingRepository;
import org.springframework.stereotype.Service;

/**
 * 자체크롤 런타임 토글 — app_setting을 매 콜 재확인(킬스위치 즉시 반영). self-enabled이고 NOT
 * force-hiker일 때만 자체 1순위. 키 부재/이상값은 안전측(false=Hiker). 프로필 표면은 og/wpi(기본 wpi).
 */
@Service
public class IgSourceSettings {

	private final AppSettingRepository settings;

	public IgSourceSettings(AppSettingRepository settings) {
		this.settings = settings;
	}

	public boolean selfEnabled() {
		if (bool("ig-source.force-hiker", false)) {
			return false;
		}
		return bool("ig-source.self-enabled", false);
	}

	public String profileSurface() {
		return settings.find("ig-source.profile-surface").filter(v -> !v.isBlank()).orElse("wpi");
	}

	private boolean bool(String key, boolean dflt) {
		return settings.find(key).map(v -> "true".equalsIgnoreCase(v.trim())).orElse(dflt);
	}
}

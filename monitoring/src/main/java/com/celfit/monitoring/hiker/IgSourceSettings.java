package com.celfit.monitoring.hiker;

import com.celfit.monitoring.store.AppSettingRepository;
import org.springframework.stereotype.Service;

/**
 * 자체크롤 런타임 토글 — app_setting을 매 콜 재확인(킬스위치 즉시 반영). self-enabled이고 NOT
 * force-hiker일 때만 자체 1순위. 키 부재/이상값은 안전측(false=Hiker). 프로필 표면은 og/wpi(기본 wpi).
 *
 * <p>댓글 GraphQL의 doc_id·friendly_name도 여기서 매 콜 재조회한다 — IG doc_id는 2~4주 주기로
 * 회전해(운영 실측) app_setting 값을 재배포 없이 바꿔 대응하기 위함. app_setting에 값이 없으면
 * env 주입(InstagramProxyProperties, monitoring.proxy.comment-doc-id 등)으로 폴백한다.
 */
@Service
public class IgSourceSettings {

	private final AppSettingRepository settings;
	private final InstagramProxyProperties proxyProps;

	public IgSourceSettings(AppSettingRepository settings, InstagramProxyProperties proxyProps) {
		this.settings = settings;
		this.proxyProps = proxyProps;
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

	public String commentDocId() {
		return settings.find("ig-source.comment-doc-id")
				.filter(v -> !v.isBlank())
				.orElse(proxyProps.commentDocId());
	}

	public String commentFriendlyName() {
		return settings.find("ig-source.comment-friendly-name")
				.filter(v -> !v.isBlank())
				.orElse(proxyProps.commentFriendlyName());
	}

	private boolean bool(String key, boolean dflt) {
		return settings.find(key).map(v -> "true".equalsIgnoreCase(v.trim())).orElse(dflt);
	}
}

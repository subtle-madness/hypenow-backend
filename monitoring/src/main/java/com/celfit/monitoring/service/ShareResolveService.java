package com.celfit.monitoring.service;

import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.MediaRef;
import com.celfit.monitoring.hiker.ShareLinkUnresolvedException;
import com.celfit.monitoring.hiker.TargetCallContext;
import java.net.URI;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * share 단축 링크 해소 — 등록과 분리된 전처리 API(계약 §2-6). RegistrationService와 무관하게
 * 독립 조립한다 — was는 등록 전에 이걸로 shortcode를 얻고, 등록은 기존 흐름 그대로 진행한다.
 */
@Service
public class ShareResolveService {

	/** www. 유무만 허용 — m.instagram.com 등 서브도메인은 대상이 아니다(실측 잔여, 계약 §2-6 각주). */
	private static final Pattern INSTAGRAM_HOST = Pattern.compile("(?i)^(www\\.)?instagram\\.com$");

	private final HikerClient hiker;
	private final TargetCallContext callContext;

	public ShareResolveService(HikerClient hiker, TargetCallContext callContext) {
		this.hiker = hiker;
		this.callContext = callContext;
	}

	/**
	 * userId는 콜 집계 귀속용(2026-08-12 비용 범위 확장) — was가 등록 전처리로 부르는 경로라 요청
	 * 유저를 안다. null이면(구 계약 본문·수동 호출) 해소는 그대로 하되 비용 미집계로 남는다.
	 */
	public MediaRef resolve(String url, Long userId) {
		validate(url);
		if (userId == null) {
			return hiker.resolveMediaByUrl(url);
		}
		return callContext.scoped(Set.of(userId), () -> hiker.resolveMediaByUrl(url));
	}

	/** http(s) instagram.com 계열이 아니면 Hiker를 부르지 않고 즉시 422로 선차단한다(콜 절약). */
	private static void validate(String url) {
		if (url == null || url.isBlank()) {
			throw new ShareLinkUnresolvedException("url이 비어 있습니다.");
		}
		URI uri;
		try {
			uri = URI.create(url.trim());
		} catch (IllegalArgumentException e) {
			throw new ShareLinkUnresolvedException("url 형식이 올바르지 않습니다: " + url);
		}
		String scheme = uri.getScheme();
		if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
			throw new ShareLinkUnresolvedException("http(s) URL이 아닙니다: " + url);
		}
		String host = uri.getHost();
		if (host == null || !INSTAGRAM_HOST.matcher(host).matches()) {
			throw new ShareLinkUnresolvedException("instagram.com 링크가 아닙니다: " + url);
		}
	}
}

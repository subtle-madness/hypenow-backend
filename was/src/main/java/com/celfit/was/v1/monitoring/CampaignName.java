package com.celfit.was.v1.monitoring;

import com.celfit.was.v1.common.V1ApiException;

/**
 * 캠페인 이름 정규화·검증(스펙 6.25 Campaign) — 이름은 라우트 세그먼트(`/monitoring/campaign/[name]`)이자
 * 추적 행과의 조인 키라 서버가 강제한다. 부작용 없는 순수 정적 유틸.
 */
public final class CampaignName {

	private static final int MAX_LENGTH = 40;

	private CampaignName() {
	}

	/**
	 * trim → 연속 공백(스페이스) 1칸 축약 → 빈 값·40자·금지 문자(/, %, 개행) 순서로 검증한다.
	 * 위반 시 유저 노출용 한국어 메시지를 담은 400(VALIDATION_FAILED)을 던진다.
	 *
	 * <p>공백 축약은 스페이스( )만 대상으로 한다(일반 공백 문자 전체를 축약하면 개행이 지워져
	 * 아래 금지 문자 검사가 무력화된다) — trim()은 양끝의 공백류(개행 포함)를 제거하되 중간의 개행은
	 * 그대로 남겨 검사에 걸리게 한다.
	 */
	public static String normalize(String raw) {
		String trimmed = raw == null ? "" : raw.trim();
		String normalized = trimmed.replaceAll(" +", " ");
		if (normalized.isEmpty()) {
			throw V1ApiException.validation("캠페인 이름을 입력해 주세요.");
		}
		if (normalized.length() > MAX_LENGTH) {
			throw V1ApiException.validation("캠페인 이름은 40자 이하여야 해요.");
		}
		if (normalized.indexOf('/') >= 0 || normalized.indexOf('%') >= 0 || normalized.indexOf('\n') >= 0) {
			throw V1ApiException.validation("캠페인 이름에는 /, %, 줄바꿈을 쓸 수 없어요.");
		}
		return normalized;
	}
}

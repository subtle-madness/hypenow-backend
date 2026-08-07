package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.v1.common.V1ApiException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 브랜드 계정명 정규화·검증(스펙 §5-1 1단계) — 부작용 없는 순수 함수.
 *
 * <p>정규화 결과가 그대로 <b>불변 저장값</b>(users.instgram_account_name)과 monitoring 등록 키가 된다.
 * 한 번 저장하면 바꿀 수 없는 값이라(§5-4) 오타·URL 붙여넣기를 여기서 전부 걸러야 한다 —
 * 프로필 URL을 그대로 넣는 오입력이 가장 흔한 케이스라 에러 메시지가 그 상황을 직접 지목한다.
 */
public final class BrandUsername {

	/** 인스타그램 핸들 문자 집합. 정규화가 소문자화까지 끝낸 뒤의 값을 검사하므로 대문자는 넣지 않는다. */
	private static final Pattern ALLOWED = Pattern.compile("^[a-z0-9._]{1,30}$");

	private static final String MESSAGE = "프로필 URL이 아닌 @를 제외한 인스타그램 계정명만 입력해주세요.";

	private BrandUsername() {
	}

	/** 앞뒤 공백 제거 → 선두 {@code @} 1개 제거 → 소문자화. null은 빈 문자열(검증에서 400으로 수렴). */
	public static String normalize(String raw) {
		if (raw == null) {
			return "";
		}
		String trimmed = raw.strip();
		if (trimmed.startsWith("@")) {
			trimmed = trimmed.substring(1);
		}
		return trimmed.toLowerCase(Locale.ROOT);
	}

	/**
	 * 정규화된 값만 받는다(호출 순서: normalize → validate). 위반은 400 VALIDATION_FAILED.
	 *
	 * <p>URL 입력(`https://www.instagram.com/x/`)·공백·한글은 문자 집합 검사에 전부 걸린다
	 * (`:`·`/`·공백·비ASCII가 집합 밖) — 별도 분기를 두지 않는다. {@code ..}만 집합 안 문자
	 * 조합이라 따로 막는다(인스타그램이 금지하는 연속 점).
	 */
	public static void validate(String normalized) {
		if (normalized == null || normalized.contains("..") || !ALLOWED.matcher(normalized).matches()) {
			throw V1ApiException.validation(MESSAGE);
		}
	}
}

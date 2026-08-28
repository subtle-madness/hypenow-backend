package com.celfit.was.v1.monitoring;

import com.celfit.was.monitoring.WeeklyEmailOptOutRepository;
import com.celfit.was.v1.common.V1ApiException;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 설정(2026-08-27 주간 개편 §5) - 이벤트 종류별 4토글 매트릭스를 <b>주간 이메일 수신
 * 토글 1개</b>로 축소했다. 저장은 옵트아웃 행(행 없음 = 수신)이고 이 서비스가 그 위에
 * `weeklyEmail` boolean 계약을 얹는다. 옵트아웃 행이 없는 유저도 get()이 기본값(true)을 내린다 -
 * 별도 유저 행 생성은 하지 않는다(행 없음 자체가 수신 상태를 뜻하므로 저장할 것이 없다).
 */
@Service
public class NotificationSettingsService {

	private static final String WEEKLY_EMAIL_KEY = "weeklyEmail";
	private static final String VALIDATION_MESSAGE = "올바른 형식이 아니에요.";

	private final WeeklyEmailOptOutRepository repository;

	public NotificationSettingsService(WeeklyEmailOptOutRepository repository) {
		this.repository = repository;
	}

	public NotificationSettingsResponse get(long userId) {
		return new NotificationSettingsResponse(!repository.isOptedOut(userId));
	}

	/**
	 * PATCH — body는 `{"weeklyEmail": <bool>}`. 그 밖의 최상위 키·boolean 아닌 값은 400
	 * VALIDATION_FAILED. 빈 바디는 아무것도 바꾸지 않고 현재 상태를 돌려준다.
	 * 검증을 먼저 끝낸 뒤 옵트아웃 행을 갱신한다(SignupService.register와 동일 관례).
	 */
	@Transactional
	public NotificationSettingsResponse patch(long userId, Map<String, Object> body) {
		Map<String, Object> safeBody = body == null ? Map.of() : body;
		for (String key : safeBody.keySet()) {
			if (!WEEKLY_EMAIL_KEY.equals(key)) {
				throw V1ApiException.validation(VALIDATION_MESSAGE);
			}
		}
		if (safeBody.containsKey(WEEKLY_EMAIL_KEY)) {
			if (!(safeBody.get(WEEKLY_EMAIL_KEY) instanceof Boolean weeklyEmail)) {
				throw V1ApiException.validation(VALIDATION_MESSAGE);
			}
			if (weeklyEmail) {
				repository.optIn(userId);
			} else {
				repository.optOut(userId);
			}
		}
		return get(userId);
	}
}

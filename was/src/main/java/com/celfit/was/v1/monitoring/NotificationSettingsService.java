package com.celfit.was.v1.monitoring;

import com.celfit.was.monitoring.EmailOptOutRepository;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.monitoring.NotificationSettingsResponse.EventSetting;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 알림 설정 매트릭스(스펙 6.33) — 저장은 EmailOptOutRepository의 옵트아웃 행(행 없음=on)이고,
 * 이 서비스가 그 위에 "이벤트 4종 완전체" 계약을 얹는다. lazy 생성 전(옵트아웃 행이 아예 없는 유저)도
 * get()이 기본값(전부 true)으로 채운 완전체를 내린다 — 별도 유저 행 생성은 하지 않는다(행 없음 자체가
 * on 상태를 뜻하므로 옵트아웃하기 전까지는 저장할 것이 없다).
 */
@Service
public class NotificationSettingsService {

	/** 이벤트 4종 — 순서 고정(스펙 6.33 예시와 동일). */
	static final List<String> EVENT_TYPES =
			List.of("collection_started", "collection_ended", "metrics_private", "content_issue");

	private static final String CONTENT_KEY = "content";
	private static final String EMAIL_KEY = "email";
	private static final String VALIDATION_MESSAGE = "올바른 형식이 아니에요.";

	private final EmailOptOutRepository repository;

	public NotificationSettingsService(EmailOptOutRepository repository) {
		this.repository = repository;
	}

	/** 4종 키 완전체 — 옵트아웃 행이 있는 유형만 email=false. */
	public NotificationSettingsResponse get(long userId) {
		Set<String> optOuts = repository.findOptOuts(userId);
		Map<String, EventSetting> content = new LinkedHashMap<>();
		for (String eventType : EVENT_TYPES) {
			content.put(eventType, new EventSetting(!optOuts.contains(eventType)));
		}
		return new NotificationSettingsResponse(content);
	}

	/**
	 * PATCH — body는 `{"content": {"<event>": {"email": <bool>}}}` 형태의 부분 객체.
	 * content 밖 키·미지 이벤트 키·email 아닌 채널 키·boolean 아닌 값은 전부 400 VALIDATION_FAILED.
	 * 검증을 먼저 전부 끝낸 뒤(all-or-nothing) 옵트아웃 행을 갱신한다 — 중간에 실패해 일부만
	 * 반영되는 것을 막는다. 반환은 get()과 동일한 전체 설정.
	 */
	public NotificationSettingsResponse patch(long userId, Map<String, Object> body) {
		Map<String, Object> safeBody = body == null ? Map.of() : body;
		for (String key : safeBody.keySet()) {
			if (!CONTENT_KEY.equals(key)) {
				throw V1ApiException.validation(VALIDATION_MESSAGE);
			}
		}

		Map<String, Boolean> changes = new LinkedHashMap<>();
		if (safeBody.containsKey(CONTENT_KEY)) {
			Object rawContent = safeBody.get(CONTENT_KEY);
			if (!(rawContent instanceof Map<?, ?> contentMap)) {
				throw V1ApiException.validation(VALIDATION_MESSAGE);
			}
			for (Map.Entry<?, ?> entry : contentMap.entrySet()) {
				String eventType = validateEventType(entry.getKey());
				boolean email = validateEmailSetting(entry.getValue());
				changes.put(eventType, email);
			}
		}

		changes.forEach((eventType, email) -> {
			if (email) {
				repository.optIn(userId, eventType);
			} else {
				repository.optOut(userId, eventType);
			}
		});

		return get(userId);
	}

	private static String validateEventType(Object rawKey) {
		if (rawKey instanceof String eventType && EVENT_TYPES.contains(eventType)) {
			return eventType;
		}
		throw V1ApiException.validation(VALIDATION_MESSAGE);
	}

	private static boolean validateEmailSetting(Object rawSetting) {
		if (!(rawSetting instanceof Map<?, ?> settingMap)) {
			throw V1ApiException.validation(VALIDATION_MESSAGE);
		}
		for (Object channelKey : settingMap.keySet()) {
			if (!EMAIL_KEY.equals(channelKey)) {
				throw V1ApiException.validation(VALIDATION_MESSAGE);
			}
		}
		if (settingMap.get(EMAIL_KEY) instanceof Boolean email) {
			return email;
		}
		throw V1ApiException.validation(VALIDATION_MESSAGE);
	}
}

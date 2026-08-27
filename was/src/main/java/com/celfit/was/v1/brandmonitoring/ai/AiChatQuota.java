package com.celfit.was.v1.brandmonitoring.ai;

import com.celfit.was.setting.AppSettingRepository;
import com.celfit.was.v1.common.V1ApiException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * 유저당 일일 질문 상한(설계 §7) - 기준값은 마이그레이션이 시드한 app_setting
 * {@value #DAILY_LIMIT_KEY}, 런타임 조정은 그 행 UPDATE로 한다.
 *
 * <p>하루 경계는 KST 자정이다 - 사용자가 체감하는 "오늘"과 맞아야 안내 문구("내일 다시")가 참이 된다.
 * 카운트 원장은 app.ai_chat_logs다(별도 카운터 테이블 없음, 설계 §6).
 *
 * <p>분당 버스트는 이 상한이 아니라 컨트롤러의 {@code RateLimiter}가 막는다 - 역할이 다르다.
 *
 * <p>컴포넌트 스캔 대상이 아니라 {@code BrandAiConfig}가 배선한다 - was에는 Clock 빈이 없어
 * {@code @Component}로 두면 킬 스위치와 무관하게 컨텍스트 기동이 깨진다(Clock은 생성자 직접 주입이
 * was 관용구다).
 */
public class AiChatQuota {

	public static final String DAILY_LIMIT_KEY = "ai.chat.daily-limit";
	public static final int DEFAULT_DAILY_LIMIT = 30;

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final Logger log = LoggerFactory.getLogger(AiChatQuota.class);

	private final AiChatLogRepository logRepository;
	private final AppSettingRepository settingRepository;
	private final Clock clock;

	public AiChatQuota(AiChatLogRepository logRepository, AppSettingRepository settingRepository, Clock clock) {
		this.logRepository = logRepository;
		this.settingRepository = settingRepository;
		this.clock = clock;
	}

	/** 상한에 도달했으면 429를 던진다. 도달 전이면 조용히 통과. */
	public void requireWithinDailyLimit(long userId) {
		int limit = dailyLimit();
		int used = logRepository.countSince(userId, startOfTodayKst());
		if (used >= limit) {
			throw new V1ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_DAILY_LIMIT_REACHED",
					"오늘 질문 가능 횟수(" + limit + "회)를 모두 사용했어요. 내일 다시 시도해 주세요.");
		}
	}

	int dailyLimit() {
		Optional<String> stored = settingRepository.findValue(DAILY_LIMIT_KEY);
		if (stored.isEmpty()) {
			return DEFAULT_DAILY_LIMIT;
		}
		try {
			return Integer.parseInt(stored.get().trim());
		} catch (NumberFormatException e) {
			log.warn("{} 값이 숫자가 아님({}) - 기본값 {}로 폴백", DAILY_LIMIT_KEY, stored.get(), DEFAULT_DAILY_LIMIT);
			return DEFAULT_DAILY_LIMIT;
		}
	}

	private OffsetDateTime startOfTodayKst() {
		return OffsetDateTime.now(clock).atZoneSameInstant(KST).toLocalDate()
				.atStartOfDay(KST).toOffsetDateTime();
	}
}

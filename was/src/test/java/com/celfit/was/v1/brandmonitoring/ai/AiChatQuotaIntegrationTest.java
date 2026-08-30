package com.celfit.was.v1.brandmonitoring.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.IntegrationTest;
import com.celfit.was.setting.AppSettingRepository;
import com.celfit.was.v1.common.V1ApiException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** 일일 질문 상한 통합 검증(설계 §7·§8) - 기준값 시드·app_setting 오버라이드·초과 429를 모두 본다. */
class AiChatQuotaIntegrationTest extends IntegrationTest {

	@Autowired
	JdbcClient jdbcClient;
	@Autowired
	ObjectMapper objectMapper;
	@Autowired
	AppSettingRepository settingRepository;

	AiChatLogRepository logRepository;
	AiChatQuota quota;
	long userId;

	@BeforeEach
	void setUp() {
		logRepository = new AiChatLogRepository(jdbcClient, objectMapper);
		// was에는 Clock 빈이 없다(생성자 직접 주입 관용구) - 실시계로 충분하다:
		// 지금 insert한 행은 항상 오늘 KST 자정 이후라 경계 결정론이 필요 없다
		quota = new AiChatQuota(logRepository, settingRepository, Clock.systemUTC());
		jdbcClient.sql("TRUNCATE app.ai_chat_logs RESTART IDENTITY CASCADE").update();
		settingRepository.upsert(AiChatQuota.DAILY_LIMIT_KEY, "30");
		userId = jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash, role, name, user_type,
				                       agreed_terms, agreed_privacy, agreed_age14)
				VALUES (:email, 'hash', 'USER', '테스터', 'brand', true, true, true)
				RETURNING id
				""").param("email", UUID.randomUUID() + "@example.com").query(Long.class).single();
	}

	@Test
	void 상한_미만이면_통과한다() {
		settingRepository.upsert(AiChatQuota.DAILY_LIMIT_KEY, "2");
		logRepository.insert(logOf());

		assertThatCode(() -> quota.requireWithinDailyLimit(userId)).doesNotThrowAnyException();
	}

	@Test
	void 상한에_도달하면_429를_던진다() {
		settingRepository.upsert(AiChatQuota.DAILY_LIMIT_KEY, "2");
		logRepository.insert(logOf());
		logRepository.insert(logOf());

		assertThatThrownBy(() -> quota.requireWithinDailyLimit(userId))
				.isInstanceOfSatisfying(V1ApiException.class, e -> {
					assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
					// FE 계약(변경요청서 §9.1)에 맞춰 분당 한도와 동일한 RATE_LIMITED로 통일한다
					assertThat(e.code()).isEqualTo("RATE_LIMITED");
					assertThat(e.getMessage()).contains("2");
				});
	}

	@Test
	void 값이_숫자가_아니면_기본값_30으로_폴백한다() {
		settingRepository.upsert(AiChatQuota.DAILY_LIMIT_KEY, "없음");

		assertThat(quota.dailyLimit()).isEqualTo(AiChatQuota.DEFAULT_DAILY_LIMIT);
	}

	@Test
	void usage는_상한_잔여_리셋시각을_돌려준다() {
		settingRepository.upsert(AiChatQuota.DAILY_LIMIT_KEY, "10");
		logRepository.insert(logOf());
		logRepository.insert(logOf());

		AiChatQuota.Usage usage = quota.usage(userId);

		assertThat(usage.dailyLimit()).isEqualTo(10);
		assertThat(usage.remaining()).isEqualTo(8);
		// 리셋 시각은 항상 KST 자정(+09:00) - 실시계 기준이라 정확한 날짜는 검증하지 않고 오프셋·시각만 본다
		assertThat(usage.resetAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
		assertThat(usage.resetAt().toLocalTime().toString()).isEqualTo("00:00");
	}

	@Test
	void usage의_remaining은_0_밑으로_내려가지_않는다() {
		settingRepository.upsert(AiChatQuota.DAILY_LIMIT_KEY, "1");
		logRepository.insert(logOf());
		logRepository.insert(logOf());

		assertThat(quota.usage(userId).remaining()).isZero();
	}

	@Test
	void usage는_llm_failed_행을_사용량에서_제외한다() {
		settingRepository.upsert(AiChatQuota.DAILY_LIMIT_KEY, "10");
		logRepository.insert(logOf());
		logRepository.insert(new AiChatLogEntry(userId, null, "실패", null, List.of(), 1, 0, 1L,
				AiChatLogEntry.OUTCOME_LLM_FAILED));

		assertThat(quota.usage(userId).remaining()).isEqualTo(9);
	}

	private AiChatLogEntry logOf() {
		return new AiChatLogEntry(userId, null, "질문", "답변", List.of(), 1, 1, 1L,
				AiChatLogEntry.OUTCOME_OK);
	}
}

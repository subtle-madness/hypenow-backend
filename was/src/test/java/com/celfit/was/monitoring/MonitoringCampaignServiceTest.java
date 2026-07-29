package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.celfit.was.IntegrationTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class MonitoringCampaignServiceTest extends IntegrationTest {

	static final OffsetDateTime EXPIRES = OffsetDateTime.parse("2026-08-28T23:59:59+09:00");
	static final KeywordRule RULE = new KeywordRule(List.of("샤넬"), List.of(), List.of());

	@Autowired
	MonitoringCampaignMappingRepository mappings;
	@Autowired
	JdbcClient jdbcClient;

	MonitoringCommandClient client;
	MonitoringCampaignService service;
	long userId;

	@BeforeEach
	void setUp() {
		client = mock(MonitoringCommandClient.class);
		service = new MonitoringCampaignService(client, mappings);
		userId = jdbcClient.sql("""
				INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id
				""")
				.param("email", "svc-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	@Test
	void 등록_성공은_선저장_후_target을_확정한다() {
		given(client.register(any())).willReturn(new RegisterResult(17L, "WATCHING", null));

		RegisterResult result = service.registerAccount(userId, "some_influencer", RULE, EXPIRES);

		assertThat(result.targetId()).isEqualTo(17L);
		assertThat(mappings.findByUserAndTarget(userId, 17L)).isPresent();
	}

	@Test
	void 전송_실패는_같은_키로_1회_재시도한다() {
		given(client.register(any()))
				.willThrow(new MonitoringUnavailableException("접속 실패", null))
				.willReturn(new RegisterResult(18L, "WATCHING", null));

		service.registerAccount(userId, "some_influencer", RULE, EXPIRES);

		// 두 호출 모두 같은 registrationKey — 멱등 replay 전제
		org.mockito.ArgumentCaptor<RegisterRequest> captor =
				org.mockito.ArgumentCaptor.forClass(RegisterRequest.class);
		verify(client, times(2)).register(captor.capture());
		assertThat(captor.getAllValues().get(0).registrationKey())
				.isEqualTo(captor.getAllValues().get(1).registrationKey());
		assertThat(mappings.findByUserAndTarget(userId, 18L)).isPresent();
	}

	@Test
	void 재시도까지_실패하면_pending_행이_남는다() {
		given(client.register(any()))
				.willThrow(new MonitoringUnavailableException("접속 실패", null));

		assertThatThrownBy(() -> service.registerAccount(userId, "some_influencer", RULE, EXPIRES))
				.isInstanceOf(MonitoringUnavailableException.class);

		// pending(target NULL) 행 유지 — 프론트 API 작업 때 키 재사용으로 마저 닫는다(스펙 §6)
		assertThat(mappings.findByUser(userId)).hasSize(1);
		assertThat(mappings.findByUser(userId).get(0).targetId()).isNull();
	}

	@Test
	void API_에러는_pending_행을_지우고_그대로_전파한다() {
		given(client.register(any()))
				.willThrow(new MonitoringApiException("SUBJECT_NOT_FOUND", "계정 없음", 404));

		assertThatThrownBy(() -> service.registerAccount(userId, "ghost", RULE, EXPIRES))
				.isInstanceOfSatisfying(MonitoringApiException.class,
						e -> assertThat(e.code()).isEqualTo("SUBJECT_NOT_FOUND"));

		// 확정 실패 = monitoring에 target 미생성 — pending 잔재 없음
		assertThat(mappings.findByUser(userId)).isEmpty();
	}

	@Test
	void 소유하지_않은_target_명령은_클라이언트_호출_전에_거부된다() {
		assertThatThrownBy(() -> service.approve(userId, 999L, 1L))
				.isInstanceOf(CampaignNotFoundException.class);
		verify(client, never()).approve(anyLong(), anyLong());
	}

	@Test
	void 소유한_target은_승인_기각_연장이_위임된다() {
		UUID key = UUID.randomUUID();
		mappings.insertPending(userId, key);
		mappings.confirmTarget(key, 17L);
		given(client.approve(17L, 3L)).willReturn(new ApproveResult(17L, "TRACKING", "DAbC"));
		given(client.reject(17L, 4L)).willReturn(new RejectResult(4L, "REJECTED"));
		given(client.extend(17L, EXPIRES)).willReturn(new ExtendResult(17L, EXPIRES));

		assertThat(service.approve(userId, 17L, 3L).status()).isEqualTo("TRACKING");
		assertThat(service.reject(userId, 17L, 4L).status()).isEqualTo("REJECTED");
		assertThat(service.extend(userId, 17L, EXPIRES).targetId()).isEqualTo(17L);
	}

	@Test
	void 삭제는_해지_성공_후에만_매핑을_지운다() {
		UUID key = UUID.randomUUID();
		mappings.insertPending(userId, key);
		mappings.confirmTarget(key, 17L);
		// 두 given()으로 나누면 두 번째 given()이 스텁 대상 메서드를 실호출하는 순간
		// 이미 걸린 willThrow가 그 자리에서 터진다 — 연속 스텁 체이닝으로 1콜:예외, 2콜:성공을 지정.
		given(client.cancel(17L))
				.willThrow(new MonitoringUnavailableException("접속 실패", null))
				.willReturn(new CancelResult(17L, "CANCELED"));

		assertThatThrownBy(() -> service.cancelAndDelete(userId, 17L))
				.isInstanceOf(MonitoringUnavailableException.class);
		assertThat(mappings.findByUserAndTarget(userId, 17L)).isPresent();   // 매핑 유지

		assertThat(service.cancelAndDelete(userId, 17L).status()).isEqualTo("CANCELED");
		assertThat(mappings.findByUserAndTarget(userId, 17L)).isEmpty();     // 성공 후 삭제
	}
}

package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * DigestJob — alarm_event 대장을 유저·KST 날짜별로 집계해 app.monitoring_digests에 upsert하는
 * 크론(갭 문서 A-1-2). MonitoringReadRepositoryTest와 같은 방식으로 monitoring-schema.sql을
 * 같은 컨테이너의 public 스키마에 올려 monitoring DB를 흉내 낸다 — DigestJob은 Spring 컨텍스트의
 * monitoring.enabled 조건부 배선과 무관하게 직접 new(MonitoringRegistrationExecutorTest와 동일 패턴).
 */
class DigestJobTest extends IntegrationTest {

	// 2026-07-30 09:30 KST — 임의 시각. 날짜 경계 테스트는 이 값 기준 KST 자정 전후로 이벤트를 심는다.
	private static final Instant NOW = OffsetDateTime.of(2026, 7, 30, 9, 30, 0, 0, ZoneOffset.ofHours(9))
			.toInstant();

	@Autowired
	DataSource dataSource;
	@Autowired
	DigestRepository digestRepository;
	@Autowired
	JdbcClient jdbcClient;

	JdbcClient monitoringJdbc;
	MonitoringReadRepository monitoringReadRepository;
	DigestJob job;

	@BeforeEach
	void setUp() throws Exception {
		try (Connection conn = dataSource.getConnection()) {
			ScriptUtils.executeSqlScript(conn, new ClassPathResource("monitoring-schema.sql"));
		}
		monitoringJdbc = JdbcClient.create(dataSource);
		monitoringJdbc.sql("TRUNCATE alarm_event RESTART IDENTITY").update();
		monitoringReadRepository = new MonitoringReadRepository(monitoringJdbc);
		Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
		job = new DigestJob(monitoringReadRepository, digestRepository, new ObjectMapper(), clock);
	}

	private long seedUser() {
		return jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "mon-digest-job-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	/** occurredAt은 UTC 컬럼(timestamptz) — KST 오프셋을 명시해 그대로 저장한다. */
	private void seedEvent(long targetId, long userId, String eventType, OffsetDateTime occurredAt) {
		monitoringJdbc.sql("""
				INSERT INTO alarm_event (target_id, user_id, event_type, payload, occurred_at, dispatch_after)
				VALUES (:targetId, :userId, :eventType, '{}'::jsonb, :occurredAt, :occurredAt)
				""")
				.param("targetId", targetId)
				.param("userId", userId)
				.param("eventType", eventType)
				.param("occurredAt", occurredAt)
				.update();
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> items(long userId) {
		DigestRow row = digestRepository.findRecentByUser(userId, 1).get(0);
		return new ObjectMapper().readValue(row.itemsJson(), List.class);
	}

	@Test
	void 유저별_1건_생성되고_유형별_count와_순서와_문구가_맞다() {
		long userA = seedUser();
		OffsetDateTime todayNoon = OffsetDateTime.of(2026, 7, 30, 12, 0, 0, 0, ZoneOffset.ofHours(9));
		// 순서를 일부러 섞어 심는다 — items[]는 시드 순서가 아니라 MonitoringEventTypes.EVENT_TYPES 고정 순서여야 한다.
		seedEvent(1, userA, "CONTENT_UNAVAILABLE", todayNoon);
		seedEvent(2, userA, "COLLECTION_STARTED", todayNoon);
		seedEvent(3, userA, "COLLECTION_STARTED", todayNoon);
		seedEvent(4, userA, "METRICS_HIDDEN", todayNoon);

		job.run();

		assertThat(digestRepository.countByUser(userA)).isEqualTo(1);
		List<Map<String, Object>> items = items(userA);
		assertThat(items).extracting(i -> i.get("type"))
				.containsExactly("collection_started", "metrics_private", "content_issue");   // 고정 순서, collection_ended는 없음(0건이라 빠짐)
		assertThat(items.get(0)).containsEntry("category", "content").containsEntry("count", 2);
		assertThat(items.get(0).get("summary")).isEqualTo("새로 수집을 시작한 콘텐츠가 있어요");
		assertThat(items.get(1)).containsEntry("count", 1);
		assertThat(items.get(1).get("summary")).isEqualTo("일부 지표가 비공개로 바뀐 콘텐츠가 있어요");
		assertThat(items.get(2).get("summary")).isEqualTo("게시물을 확인하지 못한 콘텐츠가 있어요");
	}

	@Test
	void 유저_2명이_각자_따로_집계된다() {
		long userA = seedUser();
		long userB = seedUser();
		OffsetDateTime todayNoon = OffsetDateTime.of(2026, 7, 30, 12, 0, 0, 0, ZoneOffset.ofHours(9));
		seedEvent(1, userA, "COLLECTION_STARTED", todayNoon);
		seedEvent(2, userB, "COLLECTION_ENDED", todayNoon);

		job.run();

		assertThat(items(userA)).extracting(i -> i.get("type")).containsExactly("collection_started");
		assertThat(items(userB)).extracting(i -> i.get("type")).containsExactly("collection_ended");
	}

	@Test
	void 오늘_KST_날짜가_아닌_이벤트는_집계에서_빠진다() {
		long userA = seedUser();
		OffsetDateTime yesterday = OffsetDateTime.of(2026, 7, 29, 23, 59, 0, 0, ZoneOffset.ofHours(9));
		OffsetDateTime tomorrow = OffsetDateTime.of(2026, 7, 31, 0, 0, 1, 0, ZoneOffset.ofHours(9));
		seedEvent(1, userA, "COLLECTION_STARTED", yesterday);
		seedEvent(2, userA, "COLLECTION_ENDED", tomorrow);

		job.run();

		assertThat(digestRepository.countByUser(userA)).isZero();   // 오늘 이벤트가 없으니 다이제스트 자체가 안 생긴다
	}

	@Test
	void 이벤트_0건_유저는_다이제스트가_생성되지_않는다() {
		long userA = seedUser();   // 이벤트 없음

		job.run();

		assertThat(digestRepository.countByUser(userA)).isZero();
	}

	@Test
	void 재실행하면_행이_늘지_않고_items만_최신으로_갱신된다() {
		long userA = seedUser();
		OffsetDateTime todayNoon = OffsetDateTime.of(2026, 7, 30, 12, 0, 0, 0, ZoneOffset.ofHours(9));
		seedEvent(1, userA, "COLLECTION_STARTED", todayNoon);

		job.run();
		assertThat(items(userA)).extracting(i -> i.get("type")).containsExactly("collection_started");

		// 늦게 도착한 이벤트(같은 KST 날짜) — 워터마크가 없으니 재실행이 이걸 자연히 주워야 한다.
		seedEvent(2, userA, "METRICS_HIDDEN", todayNoon);
		job.run();

		assertThat(digestRepository.countByUser(userA)).isEqualTo(1);   // 행은 그대로 1건
		assertThat(items(userA)).extracting(i -> i.get("type"))
				.containsExactly("collection_started", "metrics_private");
	}

	@Test
	void 재실행해도_read_at과_created_at은_보존된다() {
		long userA = seedUser();
		OffsetDateTime todayNoon = OffsetDateTime.of(2026, 7, 30, 12, 0, 0, 0, ZoneOffset.ofHours(9));
		seedEvent(1, userA, "COLLECTION_STARTED", todayNoon);
		job.run();
		long digestId = digestRepository.findRecentByUser(userA, 1).get(0).id();
		digestRepository.markRead(userA, List.of(digestId));
		DigestRow beforeRerun = digestRepository.findRecentByUser(userA, 1).get(0);
		assertThat(beforeRerun.readAt()).isNotNull();

		seedEvent(2, userA, "COLLECTION_ENDED", todayNoon);
		job.run();

		DigestRow afterRerun = digestRepository.findRecentByUser(userA, 1).get(0);
		assertThat(afterRerun.id()).isEqualTo(digestId);
		assertThat(afterRerun.readAt()).isEqualTo(beforeRerun.readAt());
		assertThat(afterRerun.createdAt()).isEqualTo(beforeRerun.createdAt());
	}

	@Test
	void 따라잡기_크론이_09시_이후_도착한_이벤트를_같은_날_다이제스트에_반영한다() {
		// 6.32 "배치가 9시 이후에 끝나면 늦게라도 그날 발송" — 09:00 run() 이후 스윕이 끝나
		// 이벤트가 뒤늦게 적재되는 시나리오. catchUp()이 같은 (user, date) 행을 재계산해야 한다.
		long userA = seedUser();
		OffsetDateTime todayNoon = OffsetDateTime.of(2026, 7, 30, 12, 0, 0, 0, ZoneOffset.ofHours(9));
		seedEvent(1, userA, "COLLECTION_STARTED", todayNoon);

		job.run();   // 09:00 실행
		long digestId = digestRepository.findRecentByUser(userA, 1).get(0).id();
		digestRepository.markRead(userA, List.of(digestId));
		DigestRow beforeCatchUp = digestRepository.findRecentByUser(userA, 1).get(0);
		assertThat(beforeCatchUp.readAt()).isNotNull();

		// 09:00 이후 도착한 이벤트 — 다음 "정시" 실행은 내일 날짜를 집계하므로 run()이 아니라
		// catchUp()만이 오늘 다이제스트에 반영할 수 있다.
		OffsetDateTime lateAfternoon = OffsetDateTime.of(2026, 7, 30, 14, 20, 0, 0, ZoneOffset.ofHours(9));
		seedEvent(2, userA, "CONTENT_UNAVAILABLE", lateAfternoon);
		job.catchUp();

		DigestRow afterCatchUp = digestRepository.findRecentByUser(userA, 1).get(0);
		assertThat(afterCatchUp.id()).isEqualTo(digestId);   // 새 행이 아니라 같은 행
		assertThat(items(userA)).extracting(i -> i.get("type"))
				.containsExactly("collection_started", "content_issue");
		assertThat(afterCatchUp.readAt()).isEqualTo(beforeCatchUp.readAt());   // 읽음 상태 보존
		assertThat(afterCatchUp.createdAt()).isEqualTo(beforeCatchUp.createdAt());
	}
}

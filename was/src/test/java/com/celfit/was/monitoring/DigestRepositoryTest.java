package com.celfit.was.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.was.IntegrationTest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class DigestRepositoryTest extends IntegrationTest {

	@Autowired
	DigestRepository repository;
	@Autowired
	JdbcClient jdbcClient;

	long userId;

	@BeforeEach
	void 유저_시드() {
		userId = seedUser();
	}

	private long seedUser() {
		return jdbcClient.sql("INSERT INTO app.users (email, password_hash) VALUES (:email, 'x') RETURNING id")
				.param("email", "mon-digest-" + UUID.randomUUID() + "@test.io")
				.query(Long.class).single();
	}

	@Test
	void upsert_같은_user_date_재호출은_행을_늘리지_않고_items를_갱신한다() {
		LocalDate date = LocalDate.of(2026, 7, 29);

		long first = repository.upsert(userId, date, "[]");
		long second = repository.upsert(userId, date, "[{\"category\":\"content\",\"type\":\"collection_started\",\"summary\":\"s\",\"count\":1}]");

		assertThat(second).isEqualTo(first);   // 같은 행 id — 새 행이 아니라 갱신
		assertThat(repository.countByUser(userId)).isEqualTo(1);
		assertThat(repository.findRecentByUser(userId, 10).get(0).itemsJson()).contains("collection_started");
	}

	@Test
	void upsert는_read_at과_created_at을_보존한다() {
		LocalDate date = LocalDate.of(2026, 7, 29);
		long digestId = repository.upsert(userId, date, "[]");
		repository.markRead(userId, List.of(digestId));
		DigestRow beforeRerun = repository.findRecentByUser(userId, 10).get(0);
		assertThat(beforeRerun.readAt()).isNotNull();

		repository.upsert(userId, date, "[{\"category\":\"content\",\"type\":\"collection_ended\",\"summary\":\"s\",\"count\":2}]");

		DigestRow afterRerun = repository.findRecentByUser(userId, 10).get(0);
		assertThat(afterRerun.readAt()).isEqualTo(beforeRerun.readAt());   // 늦게 도착한 이벤트를 반영해도 읽음 상태는 보존
		assertThat(afterRerun.createdAt()).isEqualTo(beforeRerun.createdAt());
		assertThat(afterRerun.itemsJson()).contains("collection_ended");
	}

	@Test
	void findRecentByUser_정렬과_limit() {
		repository.upsert(userId, LocalDate.of(2026, 7, 27), "[]");
		repository.upsert(userId, LocalDate.of(2026, 7, 29), "[]");
		repository.upsert(userId, LocalDate.of(2026, 7, 28), "[]");

		List<DigestRow> recent = repository.findRecentByUser(userId, 2);

		assertThat(recent).extracting(DigestRow::digestDate)
				.containsExactly(LocalDate.of(2026, 7, 29), LocalDate.of(2026, 7, 28));
	}

	@Test
	void markRead는_본인_소유_행만_처리한다() {
		long otherUserId = seedUser();
		long myDigestId = repository.upsert(userId, LocalDate.of(2026, 7, 29), "[]");
		long otherDigestId = repository.upsert(otherUserId, LocalDate.of(2026, 7, 29), "[]");

		// 타 유저 id·존재하지 않는 id를 섞어도 본인 것만 읽음 처리된다(멱등, 404·403 없음).
		repository.markRead(userId, List.of(myDigestId, otherDigestId, 999_999L));

		assertThat(repository.findRecentByUser(userId, 10).get(0).readAt()).isNotNull();
		assertThat(repository.findRecentByUser(otherUserId, 10).get(0).readAt()).isNull();
	}

	@Test
	void markRead는_멱등이며_최초_읽음_시각을_보존한다() throws InterruptedException {
		long digestId = repository.upsert(userId, LocalDate.of(2026, 7, 29), "[]");

		repository.markRead(userId, List.of(digestId));
		OffsetDateTime firstReadAt = repository.findRecentByUser(userId, 10).get(0).readAt();
		assertThat(firstReadAt).isNotNull();

		Thread.sleep(10); // now()가 다른 값을 찍을 여유를 준다 — 재적용돼도 안 바뀌어야 한다.
		repository.markRead(userId, List.of(digestId));
		OffsetDateTime secondReadAt = repository.findRecentByUser(userId, 10).get(0).readAt();

		assertThat(secondReadAt).isEqualTo(firstReadAt);
	}

	@Test
	void markRead_빈_리스트는_no_op() {
		long digestId = repository.upsert(userId, LocalDate.of(2026, 7, 29), "[]");

		repository.markRead(userId, List.of());

		assertThat(repository.findRecentByUser(userId, 10).get(0).readAt()).isNull();
	}

	@Test
	void markAllRead는_응답_창_30건_밖의_행까지_전부_읽음_처리한다() {
		// items는 비어있지 않은 값을 쓴다 — "[]"(클리어된 행)는 markAllRead 대상에서 빠진다
		// (2026-08-28 재리뷰 nit, 아래 markAllRead는_비워진_행을_건드리지_않는다 참조).
		LocalDate base = LocalDate.of(2026, 1, 1);
		String nonEmptyItems = "[{\"category\":\"content\",\"type\":\"collection_started\",\"summary\":\"s\",\"count\":1}]";
		for (int i = 0; i < 31; i++) {
			repository.upsert(userId, base.plusDays(i), nonEmptyItems);
		}
		assertThat(repository.countByUser(userId)).isEqualTo(31);

		repository.markAllRead(userId);

		long unreadCount = jdbcClient.sql(
				"SELECT count(*) FROM app.monitoring_digests WHERE user_id = :userId AND read_at IS NULL")
				.param("userId", userId)
				.query(Long.class)
				.single();
		assertThat(unreadCount).isZero();
	}

	@Test
	void markAllRead는_비워진_행을_건드리지_않는다() {
		// 2026-08-28 재리뷰 nit — clearItems로 비워진 행(items='[]')은 "사용자에게 존재하지
		// 않는" 것으로 취급해야 한다. 여기서도 건드리면 클리어→모두읽음→같은 주 재채움 순서가
		// 겹쳤을 때 되살아난 다이제스트가 이미 읽음 처리된 채로 노출된다.
		long visibleId = repository.upsert(userId, LocalDate.of(2026, 7, 29),
				"[{\"category\":\"content\",\"type\":\"collection_started\",\"summary\":\"s\",\"count\":1}]");
		long clearedId = repository.upsert(userId, LocalDate.of(2026, 7, 28), "[]");

		repository.markAllRead(userId);

		assertThat(repository.findRecentByUser(userId, 10).stream()
				.filter(row -> row.id() == visibleId).findFirst().orElseThrow().readAt()).isNotNull();
		assertThat(repository.findRecentByUser(userId, 10).stream()
				.filter(row -> row.id() == clearedId).findFirst().orElseThrow().readAt()).isNull();
	}

	@Test
	void countByUser() {
		assertThat(repository.countByUser(userId)).isZero();

		repository.upsert(userId, LocalDate.of(2026, 7, 29), "[]");
		repository.upsert(userId, LocalDate.of(2026, 7, 28), "[]");

		assertThat(repository.countByUser(userId)).isEqualTo(2);
	}

	@Test
	void upsertWeekly_창_시작일에_구_일일_행이_있으면_미읽음_새_행처럼_리셋한다() {
		// 품질 리뷰 C2 — 구 일일 DigestJob이 같은 digest_date(월요일)에 만든 행을 흉내낸다. 그 행의
		// created_at은 이 테스트가 실행되는 실제 지금(now())이므로, windowCloseAt을 넉넉히 미래로
		// 잡아 "행 생성 시각이 그 주 창이 닫히기 전이었다"는 리셋 조건을 재현한다.
		LocalDate weekStart = LocalDate.of(2026, 8, 17);
		OffsetDateTime windowCloseAt = OffsetDateTime.now().plusDays(7);
		long legacyId = repository.upsert(userId, weekStart, "[]");
		repository.markRead(userId, List.of(legacyId));
		assertThat(repository.findRecentByUser(userId, 1).get(0).readAt()).isNotNull();

		long weeklyId = repository.upsertWeekly(userId, weekStart, windowCloseAt,
				"[{\"category\":\"brand\",\"type\":\"brand_new_posts\",\"summary\":\"s\",\"count\":1}]");

		DigestRow after = repository.findRecentByUser(userId, 1).get(0);
		assertThat(weeklyId).isEqualTo(legacyId);   // 같은 (user, date) 행 — 새 행이 아니라 리셋
		assertThat(after.readAt()).isNull();
		assertThat(after.itemsJson()).contains("brand_new_posts");
	}

	@Test
	void upsertWeekly_같은_주_재실행은_read_at을_보존한다() {
		// windowCloseAt을 안전하게 과거로 잡아 "이 행은 창이 닫힌 뒤에 만들어진 정당한 주간 행"
		// 시나리오를 재현한다(created_at은 real now() — 항상 과거 windowCloseAt보다 뒤다).
		LocalDate weekStart = LocalDate.of(2026, 8, 17);
		OffsetDateTime windowCloseAt = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(9));
		long digestId = repository.upsertWeekly(userId, weekStart, windowCloseAt, "[]");
		repository.markRead(userId, List.of(digestId));
		DigestRow beforeRerun = repository.findRecentByUser(userId, 1).get(0);
		assertThat(beforeRerun.readAt()).isNotNull();

		long secondId = repository.upsertWeekly(userId, weekStart, windowCloseAt,
				"[{\"category\":\"content\",\"type\":\"collection_ended\",\"summary\":\"s\",\"count\":2}]");

		DigestRow afterRerun = repository.findRecentByUser(userId, 1).get(0);
		assertThat(secondId).isEqualTo(digestId);
		assertThat(afterRerun.readAt()).isEqualTo(beforeRerun.readAt());
		assertThat(afterRerun.createdAt()).isEqualTo(beforeRerun.createdAt());
		assertThat(afterRerun.itemsJson()).contains("collection_ended");
	}

	@Test
	void clearItems는_행_read_at_email_sent_at을_보존하되_노출_조회에서만_뺀다() {
		// 품질 리뷰 재리뷰 Important — delete는 email_sent_at·email_attempts까지 지워 "발송됨 →
		// 삭제 → 복구 → 재생성" 경로에서 중복 발송을 유발했다. clearItems는 items만 비운다.
		LocalDate date = LocalDate.of(2026, 7, 29);
		long digestId = repository.upsert(userId, date,
				"[{\"category\":\"content\",\"type\":\"collection_started\",\"summary\":\"s\",\"count\":1}]");
		repository.markRead(userId, List.of(digestId));
		jdbcClient.sql("UPDATE app.monitoring_digests SET email_sent_at = now(), email_attempts = 2 WHERE id = :id")
				.param("id", digestId)
				.update();

		repository.clearItems(userId, date);

		// 행 자체·read_at·email_sent_at·email_attempts는 그대로.
		assertThat(repository.countByUser(userId)).isEqualTo(1);
		DigestRow row = repository.findRecentByUser(userId, 1).get(0);
		assertThat(row.id()).isEqualTo(digestId);
		assertThat(row.readAt()).isNotNull();
		assertThat(row.itemsJson()).isEqualTo("[]");
		Object[] sentState = jdbcClient.sql(
				"SELECT email_sent_at, email_attempts FROM app.monitoring_digests WHERE id = :id")
				.param("id", digestId)
				.query((rs, rowNum) -> new Object[] {rs.getTimestamp("email_sent_at"), rs.getInt("email_attempts")})
				.single();
		assertThat(sentState[0]).isNotNull();
		assertThat(sentState[1]).isEqualTo(2);

		// 노출 조회(findVisibleRecentByUser·countVisibleByUser)에서는 빠진다.
		assertThat(repository.countVisibleByUser(userId)).isZero();
		assertThat(repository.findVisibleRecentByUser(userId, 10)).isEmpty();
	}

	@Test
	void clearItems는_행이_없으면_no_op이다() {
		LocalDate date = LocalDate.of(2026, 7, 29);

		repository.clearItems(userId, date);   // 예외 없이 통과

		assertThat(repository.countByUser(userId)).isZero();
	}

	@Test
	void findVisibleRecentByUser_countVisibleByUser는_비운_행을_제외한다() {
		// 품질 리뷰 재리뷰 Important ③ — 빈 items 행이 목록·total에서 일관되게 제외돼야 한다.
		long visibleId = repository.upsert(userId, LocalDate.of(2026, 7, 29),
				"[{\"category\":\"content\",\"type\":\"collection_started\",\"summary\":\"s\",\"count\":1}]");
		long clearedId = repository.upsert(userId, LocalDate.of(2026, 7, 28), "[]");

		assertThat(repository.countByUser(userId)).isEqualTo(2);          // 전체(내부용)는 둘 다 센다
		assertThat(repository.countVisibleByUser(userId)).isEqualTo(1);   // 노출용은 하나만
		assertThat(repository.findVisibleRecentByUser(userId, 10))
				.extracting(DigestRow::id)
				.containsExactly(visibleId)
				.doesNotContain(clearedId);
	}
}

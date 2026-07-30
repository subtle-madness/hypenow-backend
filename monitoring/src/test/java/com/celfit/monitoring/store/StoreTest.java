package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.domain.CandidateStatus;
import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
import com.celfit.monitoring.hiker.CommentInfo;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.testsupport.TestDb;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class StoreTest {

	JdbcTemplate db;
	TargetRepository targets;
	CandidateRepository candidates;
	SnapshotRepository snapshots;
	RawPayloadRepository rawPayloads;
	CommentRepository comments;
	ProfileMetaRepository profileMeta;
	PostMetaRepository postMeta;

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		targets = new TargetRepository(db);
		candidates = new CandidateRepository(db);
		snapshots = new SnapshotRepository(db);
		rawPayloads = new RawPayloadRepository(db);
		comments = new CommentRepository(db);
		profileMeta = new ProfileMetaRepository(db);
		postMeta = new PostMetaRepository(db);
	}

	@Test
	void 등록키_조회와_keyword_rule_왕복() {
		var rule = new KeywordRule(List.of("샤넬"), List.of("신상", "협찬"), List.of("이벤트"));
		long id = targets.insert(TargetType.ACCOUNT, null, "acct_a", null, rule,
				TargetStatus.WATCHING, null, "key-1", Instant.parse("2026-08-28T00:00:00Z"));
		var found = targets.findByRegistrationKey("key-1").orElseThrow();
		assertThat(found.id()).isEqualTo(id);
		// jsonb 왕복은 3목록 전부 — 한 목록만 보면 매핑이 섞여도 통과한다.
		assertThat(found.keywordRule().and()).containsExactly("샤넬");
		assertThat(found.keywordRule().any()).containsExactly("신상", "협찬");
		assertThat(found.keywordRule().exclude()).containsExactly("이벤트");
		assertThat(found.status()).isEqualTo(TargetStatus.WATCHING);
	}

	@Test
	void user_id는_저장_왕복하고_없으면_null이다() {
		var rule = new KeywordRule(List.of("샤넬"), List.of(), List.of());
		targets.insert(TargetType.ACCOUNT, 42L, "acct_a", null, rule,
				TargetStatus.WATCHING, null, "key-uid", Instant.now().plusSeconds(3600));
		// 기존 행(백필 전 운영 데이터)을 흉내 — user_id 없이 들어온 캠페인도 그대로 저장된다.
		targets.insert(TargetType.ACCOUNT, null, "acct_b", null, rule,
				TargetStatus.WATCHING, null, "key-nouid", Instant.now().plusSeconds(3600));

		assertThat(targets.findByRegistrationKey("key-uid").orElseThrow().userId()).isEqualTo(42L);
		assertThat(targets.findByRegistrationKey("key-nouid").orElseThrow().userId()).isNull();
	}

	@Test
	void 같은_후보는_한_번만_생성() {
		long id = targets.insert(TargetType.ACCOUNT, null, "acct_a", null,
				new KeywordRule(List.of("샤넬"), List.of(), List.of()),
				TargetStatus.WATCHING, null, "key-2", Instant.now().plusSeconds(3600));
		candidates.insertPending(id, "SC1", "…샤넬…", List.of("샤넬"));
		candidates.insertPending(id, "SC1", "…샤넬…", List.of("샤넬"));
		assertThat(db.queryForObject("SELECT count(*) FROM detected_candidate", Long.class)).isEqualTo(1);
	}

	@Test
	void 후보_상태_전이() {
		long targetId = targets.insert(TargetType.ACCOUNT, null, "acct_a", null,
				new KeywordRule(List.of("샤넬"), List.of(), List.of()),
				TargetStatus.WATCHING, null, "key-4", Instant.now().plusSeconds(3600));
		candidates.insertPending(targetId, "SC9", "…샤넬…", List.of("샤넬"));
		long candidateId = db.queryForObject("SELECT id FROM detected_candidate WHERE short_code='SC9'", Long.class);

		var pending = candidates.find(candidateId).orElseThrow();
		assertThat(pending.targetId()).isEqualTo(targetId);
		assertThat(pending.status()).isEqualTo(CandidateStatus.PENDING);

		candidates.setStatus(candidateId, CandidateStatus.APPROVED);
		assertThat(candidates.find(candidateId).orElseThrow().status()).isEqualTo(CandidateStatus.APPROVED);
	}

	@Test
	void 거절된_후보는_재감지돼도_되살아나지_않는다() {
		long targetId = targets.insert(TargetType.ACCOUNT, null, "acct_a", null,
				new KeywordRule(List.of("샤넬"), List.of(), List.of()),
				TargetStatus.WATCHING, null, "key-5", Instant.now().plusSeconds(3600));
		candidates.insertPending(targetId, "SC7", "…샤넬…", List.of("샤넬"));
		long candidateId = db.queryForObject("SELECT id FROM detected_candidate WHERE short_code='SC7'", Long.class);
		candidates.setStatus(candidateId, CandidateStatus.REJECTED);

		// 다음 스윕에서 같은 게시물이 또 걸려도 DO NOTHING — PENDING으로 되돌아가지 않는다.
		candidates.insertPending(targetId, "SC7", "…샤넬…", List.of("샤넬"));

		assertThat(db.queryForObject("SELECT count(*) FROM detected_candidate", Long.class)).isEqualTo(1);
		assertThat(candidates.find(candidateId).orElseThrow().status()).isEqualTo(CandidateStatus.REJECTED);
	}

	@Test
	void 스냅샷은_일_1회_upsert() {
		var post = new PostInfo("SC1", "acct_a", "REELS", "캡션", null, 1753670000L, 10L, 2L, 100L, null, null, null, "{}", true);
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), post);
		var post2 = new PostInfo("SC1", "acct_a", "REELS", "캡션", null, 1753670000L, 12L, 3L, 110L, null, null, null, "{}", true);
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), post2);
		assertThat(db.queryForObject(
				"SELECT likes FROM post_snapshot WHERE short_code='SC1'", Long.class)).isEqualTo(12);
	}

	@Test
	void 프로필_스냅샷도_일_1회_upsert() {
		snapshots.upsertProfile("acct_a", LocalDate.of(2026, 7, 28),
				new ProfileInfo("acct_a", "1", 100L, 10L, 5L, "이름", "https://img", "{}"));
		snapshots.upsertProfile("acct_a", LocalDate.of(2026, 7, 28),
				new ProfileInfo("acct_a", "1", 120L, 11L, 6L, "이름", "https://img", "{}"));
		assertThat(db.queryForObject("SELECT count(*) FROM profile_snapshot", Long.class)).isEqualTo(1);
		assertThat(db.queryForObject(
				"SELECT followers FROM profile_snapshot WHERE username='acct_a'", Long.class)).isEqualTo(120);
	}

	@Test
	void 만료_스윕은_활성만_EXPIRED로() {
		targets.insert(TargetType.POST, null, "acct_a", "SC1", null,
				TargetStatus.TRACKING, "SC1", "key-3", Instant.now().minusSeconds(60));
		// 이미 종결된 행도 만기는 지났다 — 활성 필터가 없으면 이 행까지 EXPIRED로 덮인다.
		targets.insert(TargetType.POST, null, "acct_b", "SC2", null,
				TargetStatus.CANCELED, "SC2", "key-3b", Instant.now().minusSeconds(60));

		var expired = targets.expireOverdue();

		assertThat(expired).hasSize(1);
		assertThat(expired.getFirst().username()).isEqualTo("acct_a");
		assertThat(expired.getFirst().trackedShortCode()).isEqualTo("SC1");
		assertThat(targets.findActive()).isEmpty();
		assertThat(targets.findByRegistrationKey("key-3").orElseThrow().status())
				.isEqualTo(TargetStatus.EXPIRED);
		assertThat(targets.findByRegistrationKey("key-3b").orElseThrow().status())
				.isEqualTo(TargetStatus.CANCELED);
	}

	@Test
	void 원형_응답은_jsonb로_적재된다() {
		rawPayloads.save("PROFILE", "acct_a", 200, "{\"username\":\"acct_a\"}");
		assertThat(db.queryForObject("""
				SELECT payload ->> 'username' FROM raw.fetch_payload
				WHERE kind='PROFILE' AND subject='acct_a' AND http_status=200""", String.class))
				.isEqualTo("acct_a");
	}

	// ── matched_keywords(v1.1) ──────────────────────────────────────────────

	@Test
	void matched_keywords는_jsonb로_저장되고_조회된다() {
		long id = targets.insert(TargetType.ACCOUNT, null, "acct_a", null,
				new KeywordRule(List.of("샤넬"), List.of("립스틱"), List.of()),
				TargetStatus.WATCHING, null, "key-mk1", Instant.now().plusSeconds(3600));
		candidates.insertPending(id, "SCK1", "…샤넬 립스틱…", List.of("샤넬", "립스틱"));

		String json = db.queryForObject("""
				SELECT matched_keywords::text FROM detected_candidate WHERE target_id=?""",
				String.class, id);
		assertThat(json).contains("샤넬", "립스틱");
	}

	/** v1.1 이전 감지분과 동일한 상황(매칭 키워드를 안 넘긴 경우) — was가 null이면 빈 배열로 폴백한다(계약 §3). */
	@Test
	void matched_keywords를_주지_않으면_컬럼도_null이다() {
		long id = targets.insert(TargetType.ACCOUNT, null, "acct_a", null,
				new KeywordRule(List.of("샤넬"), List.of(), List.of()),
				TargetStatus.WATCHING, null, "key-mk2", Instant.now().plusSeconds(3600));
		candidates.insertPending(id, "SCK2", "…샤넬…", null);

		Boolean isNull = db.queryForObject("""
				SELECT matched_keywords IS NULL FROM detected_candidate WHERE target_id=?""",
				Boolean.class, id);
		assertThat(isNull).isTrue();
	}

	// ── post_comment(v1.1) ───────────────────────────────────────────────────

	/** 이전 id는 보존되고 재관측 시 body·like_count·owner_reply_text만 갱신된다(누적 합집합 — 계약 §3 post_comment). */
	@Test
	void 댓글은_게시물당_누적_합집합으로_upsert된다() {
		var first = new CommentInfo("1", "user1", "본문1", 5L, Instant.parse("2026-07-28T00:00:00Z"), null);
		comments.upsertForPost("SC1", List.of(first));
		assertThat(db.queryForObject(
				"SELECT count(*) FROM post_comment WHERE short_code='SC1'", Long.class)).isEqualTo(1);

		// 다음 수집에서 새 댓글(2)이 추가되고, 같은 댓글(1)은 like_count·body가 갱신된다.
		var firstUpdated = new CommentInfo("1", "user1", "본문1 수정", 9L, Instant.parse("2026-07-28T00:00:00Z"), null);
		var second = new CommentInfo("2", "user2", "본문2", 1L, Instant.parse("2026-07-29T00:00:00Z"), "답글");
		comments.upsertForPost("SC1", List.of(firstUpdated, second));

		var ids = db.queryForList(
				"SELECT id FROM post_comment WHERE short_code='SC1' ORDER BY id", String.class);
		assertThat(ids).containsExactly("1", "2");   // 이전 수집분(1)이 보존되고 이번 수집분(2)이 추가된다
		assertThat(db.queryForObject(
				"SELECT like_count FROM post_comment WHERE short_code='SC1' AND id='1'", Long.class))
				.isEqualTo(9L);
		assertThat(db.queryForObject(
				"SELECT body FROM post_comment WHERE short_code='SC1' AND id='1'", String.class))
				.isEqualTo("본문1 수정");
		assertThat(db.queryForObject(
				"SELECT owner_reply_text FROM post_comment WHERE short_code='SC1' AND id='2'", String.class))
				.isEqualTo("답글");
	}

	@Test
	void 댓글_upsert는_다른_게시물에_영향을_주지_않는다() {
		comments.upsertForPost("SC1", List.of(
				new CommentInfo("1", "user1", "본문", 1L, Instant.now(), null)));
		comments.upsertForPost("SC2", List.of(
				new CommentInfo("2", "user2", "본문", 1L, Instant.now(), null)));

		// SC1에 새 댓글을 추가해도 SC2는 그대로다.
		comments.upsertForPost("SC1", List.of(
				new CommentInfo("3", "user3", "본문", 1L, Instant.now(), null)));

		assertThat(db.queryForObject(
				"SELECT count(*) FROM post_comment WHERE short_code='SC1'", Long.class)).isEqualTo(2);
		assertThat(db.queryForObject(
				"SELECT count(*) FROM post_comment WHERE short_code='SC2'", Long.class)).isEqualTo(1);
	}

	// ── profile_meta(v1.1) ───────────────────────────────────────────────────

	@Test
	void 프로필_메타는_upsert된다() {
		profileMeta.upsert("acct_a", "표시이름", "https://img/1.jpg", LocalDate.of(2026, 7, 20));

		var row = db.queryForMap("SELECT * FROM profile_meta WHERE username='acct_a'");
		assertThat(row.get("display_name")).isEqualTo("표시이름");
		assertThat(row.get("profile_image_url")).isEqualTo("https://img/1.jpg");
		assertThat(row.get("last_uploaded_at")).isEqualTo(java.sql.Date.valueOf(LocalDate.of(2026, 7, 20)));

		profileMeta.upsert("acct_a", "새이름", "https://img/2.jpg", LocalDate.of(2026, 7, 25));
		var updated = db.queryForMap("SELECT * FROM profile_meta WHERE username='acct_a'");
		assertThat(updated.get("display_name")).isEqualTo("새이름");
		assertThat(updated.get("last_uploaded_at")).isEqualTo(java.sql.Date.valueOf(LocalDate.of(2026, 7, 25)));
	}

	/** 열거 0건(POST 단독 스윕 등)으로 lastUploadedAt이 null이면 기존 최근 게시일을 지우지 않는다. */
	@Test
	void last_uploaded_at이_null이면_기존_값을_보존한다() {
		profileMeta.upsert("acct_a", "이름", "https://img", LocalDate.of(2026, 7, 20));

		profileMeta.upsert("acct_a", "이름", "https://img", null);

		assertThat(db.queryForObject(
				"SELECT last_uploaded_at FROM profile_meta WHERE username='acct_a'", LocalDate.class))
				.isEqualTo(LocalDate.of(2026, 7, 20));
	}

	// ── post_meta(v2.2) ──────────────────────────────────────────────────────

	@Test
	void 게시물_메타는_신규_삽입된다() {
		postMeta.upsert("SC1", "acct_a", "REELS", LocalDate.of(2026, 7, 28), "캡션 원문", "https://cdn/thumb1.jpg");

		var row = db.queryForMap("SELECT * FROM post_meta WHERE short_code='SC1'");
		assertThat(row.get("username")).isEqualTo("acct_a");
		assertThat(row.get("content_type")).isEqualTo("REELS");
		assertThat(row.get("uploaded_at")).isEqualTo(java.sql.Date.valueOf(LocalDate.of(2026, 7, 28)));
		assertThat(row.get("caption")).isEqualTo("캡션 원문");
		assertThat(row.get("thumbnail_url")).isEqualTo("https://cdn/thumb1.jpg");
		assertThat(row.get("first_seen_at")).isNotNull();
	}

	/** 재수집은 캡션·썸네일을 덮어쓴다(수정 반영) — first_seen_at만 최초 관측을 보존한다. */
	@Test
	void 게시물_메타_갱신은_캡션_썸네일을_덮고_first_seen_at은_보존한다() {
		postMeta.upsert("SC1", "acct_a", "REELS", LocalDate.of(2026, 7, 28), "캡션 원문", "https://cdn/thumb1.jpg");
		var firstSeenAt = db.queryForObject(
				"SELECT first_seen_at FROM post_meta WHERE short_code='SC1'", java.sql.Timestamp.class);

		postMeta.upsert("SC1", "acct_a", "REELS", LocalDate.of(2026, 7, 28), "캡션 수정본", "https://cdn/thumb2.jpg");

		var row = db.queryForMap("SELECT * FROM post_meta WHERE short_code='SC1'");
		assertThat(row.get("caption")).isEqualTo("캡션 수정본");
		assertThat(row.get("thumbnail_url")).isEqualTo("https://cdn/thumb2.jpg");
		assertThat(row.get("first_seen_at")).isEqualTo(firstSeenAt);
	}

	/** 일시적으로 썸네일을 못 얻은 수집이 기존 유효 URL을 지우면 안 된다(계약 §3 post_meta). */
	@Test
	void 썸네일_null_수집은_기존_썸네일_url을_보존한다() {
		postMeta.upsert("SC1", "acct_a", "REELS", LocalDate.of(2026, 7, 28), "캡션", "https://cdn/thumb1.jpg");

		postMeta.upsert("SC1", "acct_a", "REELS", LocalDate.of(2026, 7, 29), "캡션 갱신", null);

		var row = db.queryForMap("SELECT * FROM post_meta WHERE short_code='SC1'");
		assertThat(row.get("thumbnail_url")).isEqualTo("https://cdn/thumb1.jpg");
		assertThat(row.get("caption")).isEqualTo("캡션 갱신");   // 캡션은 그대로 덮인다
	}

	// ── hidden/error 신호·matched_keywords(v2.2) ────────────────────────────

	@Test
	void markTracking은_matched_keywords를_jsonb로_기록한다() {
		long id = targets.insert(TargetType.ACCOUNT, 7L, "acct_a", null,
				new KeywordRule(List.of("샤넬"), List.of(), List.of()),
				TargetStatus.WATCHING, null, "key-mt1", Instant.now().plusSeconds(3600));

		targets.markTracking(id, "SC1", List.of("샤넬", "립스틱"));

		var row = targets.findById(id).orElseThrow();
		assertThat(row.status()).isEqualTo(TargetStatus.TRACKING);
		assertThat(row.trackedShortCode()).isEqualTo("SC1");
		String json = db.queryForObject("SELECT matched_keywords::text FROM target WHERE id=?", String.class, id);
		assertThat(json).contains("샤넬", "립스틱");
	}

	/** markHidden은 null→값 전이일 때만 true — 이미 hidden인 target의 재호출은 전이가 아니라 false다. */
	@Test
	void markHidden은_최초_호출만_전이를_반환한다() {
		long id = targets.insert(TargetType.ACCOUNT, 7L, "acct_a", null,
				new KeywordRule(List.of("샤넬"), List.of(), List.of()),
				TargetStatus.WATCHING, null, "key-h1", Instant.now().plusSeconds(3600));

		assertThat(targets.markHidden(id)).isTrue();
		assertThat(targets.markHidden(id)).isFalse();   // 이미 hidden — 재호출은 전이가 아니다
		assertThat(targets.findById(id).orElseThrow().trackedHiddenAt()).isNotNull();
	}

	/** markFetchFailing은 false→true로 실제 전이된 행만 반환한다 — 이미 failing인 target은 빠진다. */
	@Test
	void markFetchFailing은_전이된_행만_반환한다() {
		long a = targets.insert(TargetType.ACCOUNT, 7L, "acct_a", null,
				new KeywordRule(List.of("샤넬"), List.of(), List.of()),
				TargetStatus.WATCHING, null, "key-f1", Instant.now().plusSeconds(3600));
		long b = targets.insert(TargetType.ACCOUNT, 7L, "acct_b", null,
				new KeywordRule(List.of("샤넬"), List.of(), List.of()),
				TargetStatus.WATCHING, null, "key-f2", Instant.now().plusSeconds(3600));

		var firstCall = targets.markFetchFailing(List.of(a, b));
		assertThat(firstCall).extracting(FailingTarget::id).containsExactlyInAnyOrder(a, b);

		var secondCall = targets.markFetchFailing(List.of(a, b));   // 둘 다 이미 failing이라 갱신 대상이 없다
		assertThat(secondCall).isEmpty();
	}

	@Test
	void markFetchFailing에_빈_컬렉션을_주면_쿼리_없이_빈_목록이다() {
		assertThat(targets.markFetchFailing(List.of())).isEmpty();
	}

	/** touchFetched(수집 성공)이 hidden·fetch_failing 두 신호의 유일한 복귀 지점이다(계약 §3). */
	@Test
	void touchFetched은_hidden과_fetch_failing을_동시에_복귀시킨다() {
		long id = targets.insert(TargetType.ACCOUNT, 7L, "acct_a", null,
				new KeywordRule(List.of("샤넬"), List.of(), List.of()),
				TargetStatus.WATCHING, null, "key-t1", Instant.now().plusSeconds(3600));
		targets.markHidden(id);
		targets.markFetchFailing(List.of(id));

		targets.touchFetched(id);

		var row = targets.findById(id).orElseThrow();
		assertThat(row.trackedHiddenAt()).isNull();
		assertThat(row.fetchFailing()).isFalse();
		assertThat(db.queryForObject(
				"SELECT last_fetched_at IS NOT NULL FROM target WHERE id=?", Boolean.class, id)).isTrue();
	}
}

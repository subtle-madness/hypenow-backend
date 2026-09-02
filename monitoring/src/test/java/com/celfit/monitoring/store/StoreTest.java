package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.instagram.source.CommentInfo;
import com.celfit.instagram.source.PostInfo;
import com.celfit.instagram.source.ProfileInfo;
import com.celfit.monitoring.domain.CandidateStatus;
import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
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
		var post = new PostInfo("SC1", "acct_a", null, null, null, "REELS", "캡션", null, 1753670000L, 10L, 2L, 100L, null, null, null, null, null, null, null, true, false, false);
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), post);
		var post2 = new PostInfo("SC1", "acct_a", null, null, null, "REELS", "캡션", null, 1753670000L, 12L, 3L, 110L, null, null, null, null, null, null, null, true, false, false);
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), post2);
		assertThat(db.queryForObject(
				"SELECT likes FROM post_snapshot WHERE short_code='SC1'", Long.class)).isEqualTo(12);
	}

	/** 숨김 관측은 likes=null + likes_hidden=true로 저장 — 해제 관측이 오면 false로 덮인다. */
	@Test
	void 좋아요_숨김_플래그는_저장되고_해제되면_덮인다() {
		var hidden = new PostInfo("SC1", "acct_a", null, null, null, "REELS", "캡션", null, 1753670000L,
				null, 2L, 100L, null, null, null, null, null, null, null, true, true, false);
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), hidden);
		assertThat(db.queryForMap("SELECT likes, likes_hidden FROM post_snapshot WHERE short_code='SC1'"))
				.containsEntry("likes", null).containsEntry("likes_hidden", true);

		var visible = new PostInfo("SC1", "acct_a", null, null, null, "REELS", "캡션", null, 1753670000L,
				90L, 2L, 100L, null, null, null, null, null, null, null, true, false, false);
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), visible);
		assertThat(db.queryForMap("SELECT likes, likes_hidden FROM post_snapshot WHERE short_code='SC1'"))
				.containsEntry("likes", 90L).containsEntry("likes_hidden", false);
	}

	/** 공유 숨김 관측(08-05)은 shares_hidden=true로 저장 — 해제 관측이 오면 false로 덮인다. */
	@Test
	void 공유_숨김_플래그는_저장되고_해제되면_덮인다() {
		var hidden = new PostInfo("SC1", "acct_a", null, null, null, "REELS", "캡션", null, 1753670000L,
				10L, 2L, 100L, null, null, null, null, null, null, null, true, false, true);
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), hidden);
		assertThat(db.queryForMap("SELECT shares, shares_hidden FROM post_snapshot WHERE short_code='SC1'"))
				.containsEntry("shares", null).containsEntry("shares_hidden", true);

		var visible = new PostInfo("SC1", "acct_a", null, null, null, "REELS", "캡션", null, 1753670000L,
				10L, 2L, 100L, null, null, 7L, null, null, null, null, true, false, false);
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), visible);
		assertThat(db.queryForMap("SELECT shares, shares_hidden FROM post_snapshot WHERE short_code='SC1'"))
				.containsEntry("shares", 7L).containsEntry("shares_hidden", false);
	}

	// ── 같은 날 재수집 시 null 관측의 덮어쓰기 보호(데이터 보호 결함 수정) ──────
	// self 단건(embed)은 saves/shares/reposts를 구조적으로 항상 null 반환한다 — 같은 날 Hiker가
	// 채운 값 위에 self가 재수집하면 null로 덮이던 결함(fb_plays 캐리포워드와 동일 원칙 적용).

	/** saves·shares·reposts는 EXCLUDED가 null이면 기존값을 유지한다(views/fb_plays 캐리포워드와 동형). */
	@Test
	void 저장_공유_리포스트_null_관측은_기존_값을_보존한다() {
		var full = new PostInfo("SC1", "acct_a", null, null, null, "REELS", "캡션", null, 1753670000L,
				10L, 2L, 100L, null, 3L, 4L, 5L, null, null, null, true, false, false);
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), full);

		var selfOnly = new PostInfo("SC1", "acct_a", null, null, null, "REELS", "캡션", null, 1753670000L,
				10L, 2L, 100L, null, null, null, null, null, null, null, true, false, false);
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), selfOnly);

		assertThat(db.queryForMap("SELECT saves, shares, reposts FROM post_snapshot WHERE short_code='SC1'"))
				.containsEntry("saves", 3L).containsEntry("shares", 4L).containsEntry("reposts", 5L);
	}

	/** comments의 null은 "파싱 실패"뿐이다(정상적으로 숨길 수 없는 값) — 동일 보호 적용. */
	@Test
	void 댓글수_null_관측은_기존_값을_보존한다() {
		var known = new PostInfo("SC1", "acct_a", null, null, null, "REELS", "캡션", null, 1753670000L,
				10L, 5L, 100L, null, null, null, null, null, null, null, true, false, false);
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), known);

		var failed = new PostInfo("SC1", "acct_a", null, null, null, "REELS", "캡션", null, 1753670000L,
				10L, null, 100L, null, null, null, null, null, null, null, true, false, false);
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), failed);

		assertThat(db.queryForMap("SELECT comments FROM post_snapshot WHERE short_code='SC1'"))
				.containsEntry("comments", 5L);
	}

	/**
	 * likes=null인데 likes_hidden=false인 관측(self의 정규식 파싱 실패를 "숨김 단정"하지 않도록 고친
	 * 결과 — 수정 2)은 "미확정"으로 취급해 기존 좋아요·숨김 상태를 보존한다. likes_hidden=true(진짜
	 * 숨김 관측)는 보호 대상이 아니다(아래 테스트로 구분).
	 */
	@Test
	void 좋아요_미확정_null은_기존_값을_보존한다() {
		var known = new PostInfo("SC1", "acct_a", null, null, null, "REELS", "캡션", null, 1753670000L,
				50L, 2L, 100L, null, null, null, null, null, null, null, true, false, false);
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), known);

		var ambiguous = new PostInfo("SC1", "acct_a", null, null, null, "REELS", "캡션", null, 1753670000L,
				null, 2L, 100L, null, null, null, null, null, null, null, true, false, false);
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), ambiguous);

		assertThat(db.queryForMap("SELECT likes, likes_hidden FROM post_snapshot WHERE short_code='SC1'"))
				.containsEntry("likes", 50L).containsEntry("likes_hidden", false);
	}

	/** likes_hidden=true(진짜 숨김 관측)는 "미확정" 보호 대상이 아니라 정상적으로 기존 값을 덮는다. */
	@Test
	void 좋아요_실제_숨김_관측은_기존_값을_null로_덮는다() {
		var known = new PostInfo("SC1", "acct_a", null, null, null, "REELS", "캡션", null, 1753670000L,
				50L, 2L, 100L, null, null, null, null, null, null, null, true, false, false);
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), known);

		var hidden = new PostInfo("SC1", "acct_a", null, null, null, "REELS", "캡션", null, 1753670000L,
				null, 2L, 100L, null, null, null, null, null, null, null, true, true, false);
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), hidden);

		assertThat(db.queryForMap("SELECT likes, likes_hidden FROM post_snapshot WHERE short_code='SC1'"))
				.containsEntry("likes", null).containsEntry("likes_hidden", true);
	}

	/** shares_hidden=true(진짜 숨김 관측)도 likes와 동형 — 보호 대상이 아니라 정상적으로 기존 값을 덮는다. */
	@Test
	void 공유_실제_숨김_관측은_기존_값을_null로_덮는다() {
		var known = new PostInfo("SC1", "acct_a", null, null, null, "REELS", "캡션", null, 1753670000L,
				10L, 2L, 100L, null, null, 7L, null, null, null, null, true, false, false);
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), known);

		var hidden = new PostInfo("SC1", "acct_a", null, null, null, "REELS", "캡션", null, 1753670000L,
				10L, 2L, 100L, null, null, null, null, null, null, null, true, false, true);
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), hidden);

		assertThat(db.queryForMap("SELECT shares, shares_hidden FROM post_snapshot WHERE short_code='SC1'"))
				.containsEntry("shares", null).containsEntry("shares_hidden", true);
	}

	// ── 0 캐리 판정(08-05) — 구조적 키 부재 게시물의 매일 헛 재시도 차단 ────────
	// 양수 관측 이력이 전무하고 전일 행이 0으로 끝났으면(0 간주 산물) 오늘은 재시도 없이 0을
	// 잇는다. 양수 이력이 있거나 전일이 null(전부 꽝 이월)이면 대상이 아니다 — 근거 없는 캐리 금지.

	private void seedSnapshotRow(String code, String day, Long shares, Long reposts) {
		db.update("""
				INSERT INTO post_snapshot (username, short_code, captured_on, content_type, saves, shares, reposts)
				VALUES ('acct_a', ?, ?::date, 'REELS', 1, ?, ?)""", code, day, shares, reposts);
	}

	@Test
	void 리포스트_0_캐리는_양수_이력_없이_전일_0으로_끝난_게시물만_잡는다() {
		seedSnapshotRow("CARRY", "2026-07-27", 3L, 0L);    // 전일 0 간주 산물 → 대상
		seedSnapshotRow("POSITIVE", "2026-07-27", 3L, 5L); // 양수 실측 이력 → 제외
		seedSnapshotRow("MISSED", "2026-07-27", 3L, null); // 전부 꽝 이월(null) → 제외
		var today = LocalDate.of(2026, 7, 28);

		assertThat(snapshots.codesWithRepostsZeroCarry(
				java.util.List.of("CARRY", "POSITIVE", "MISSED", "NOROW"), today))
				.containsExactly("CARRY");
	}

	@Test
	void 리포스트_0_캐리는_과거_양수_이력이_있으면_전일이_0이어도_제외한다() {
		seedSnapshotRow("ONCE", "2026-07-26", 3L, 7L);     // 과거 실측 7 — 키가 오는 게시물
		seedSnapshotRow("ONCE", "2026-07-27", 3L, 0L);
		assertThat(snapshots.codesWithRepostsZeroCarry(
				java.util.List.of("ONCE"), LocalDate.of(2026, 7, 28))).isEmpty();
	}

	@Test
	void 공유_0_캐리도_같은_규칙으로_판정한다() {
		seedSnapshotRow("SCARRY", "2026-07-27", 0L, 1L);
		seedSnapshotRow("SPOS", "2026-07-27", 9L, 1L);
		assertThat(snapshots.codesWithSharesZeroCarry(
				java.util.List.of("SCARRY", "SPOS"), LocalDate.of(2026, 7, 28)))
				.containsExactly("SCARRY");
	}

	// ── 조회수 세션 일관성(08-03, findings §2 결론 4) ─────────────────────────
	// PostInfo.views는 IG 몫, fbPlays는 FB 교차게시 몫(null=미관측/0=관측된 0).
	// 저장되는 views는 화면 합산값 = IG 몫 + fb — fb가 이번 콜에 안 실렸으면(IG 전용 세션)
	// 직전 관측 fb_plays를 캐리포워드한다(FB 몫은 실측상 거의 정적 — 며칠 단위 고정).

	private static PostInfo reels(Long igViews, Long fbPlays) {
		return new PostInfo("SC1", "acct_a", null, null, null, "REELS", "캡션", null, 1753670000L,
				10L, 2L, igViews, fbPlays, null, null, null, null, null, null, true, false, false);
	}

	@Test
	void 릴스_views는_IG몫에_fb몫을_합산한_화면값으로_저장된다() {
		snapshots.upsertPost(LocalDate.of(2026, 8, 1), reels(100L, 40L));
		assertThat(db.queryForMap("SELECT views, fb_plays FROM post_snapshot WHERE captured_on='2026-08-01'"))
				.containsEntry("views", 140L).containsEntry("fb_plays", 40L);
	}

	@Test
	void fb_미관측_콜은_직전_fb몫을_캐리포워드한다() {
		snapshots.upsertPost(LocalDate.of(2026, 8, 1), reels(100L, 40L));
		snapshots.upsertPost(LocalDate.of(2026, 8, 2), reels(110L, null));   // IG 전용 세션에 걸린 날
		assertThat(db.queryForMap("SELECT views, fb_plays FROM post_snapshot WHERE captured_on='2026-08-02'"))
				.containsEntry("views", 150L).containsEntry("fb_plays", 40L);
		// 다시 합산 세션에 걸리면 신규 관측이 캐리포워드를 덮는다
		snapshots.upsertPost(LocalDate.of(2026, 8, 3), reels(120L, 45L));
		assertThat(db.queryForMap("SELECT views, fb_plays FROM post_snapshot WHERE captured_on='2026-08-03'"))
				.containsEntry("views", 165L).containsEntry("fb_plays", 45L);
	}

	@Test
	void fb_0_관측은_캐리포워드가_아니라_0으로_덮는다() {
		snapshots.upsertPost(LocalDate.of(2026, 8, 1), reels(100L, 40L));
		snapshots.upsertPost(LocalDate.of(2026, 8, 2), reels(110L, 0L));   // 관측된 0(교차게시 해제 등)
		assertThat(db.queryForMap("SELECT views, fb_plays FROM post_snapshot WHERE captured_on='2026-08-02'"))
				.containsEntry("views", 110L).containsEntry("fb_plays", 0L);
	}

	/**
	 * 역전파(08-03) — fb를 "처음" 관측하는 날, 그 이전의 미관측 행들은 IG 전용이라 시계열에
	 * 유령 점프(+fb)가 생긴다(성과 추이 차트가 이를 "▲증가"로 오표시). FB 몫은 실측상 정적이므로
	 * 첫 관측값을 이전 미관측 행에 소급 적용해 시계열을 합산 기준으로 정렬한다.
	 */
	@Test
	void fb_첫_관측은_이전_미관측_행에_소급_적용된다() {
		snapshots.upsertPost(LocalDate.of(2026, 8, 1), reels(100L, null));   // IG 전용 세션
		snapshots.upsertPost(LocalDate.of(2026, 8, 2), reels(110L, null));
		snapshots.upsertPost(LocalDate.of(2026, 8, 3), reels(120L, 40L));    // 첫 fb 관측
		assertThat(db.queryForMap("SELECT views, fb_plays FROM post_snapshot WHERE captured_on='2026-08-01'"))
				.containsEntry("views", 140L).containsEntry("fb_plays", 40L);
		assertThat(db.queryForMap("SELECT views, fb_plays FROM post_snapshot WHERE captured_on='2026-08-02'"))
				.containsEntry("views", 150L).containsEntry("fb_plays", 40L);
		assertThat(db.queryForMap("SELECT views, fb_plays FROM post_snapshot WHERE captured_on='2026-08-03'"))
				.containsEntry("views", 160L).containsEntry("fb_plays", 40L);
	}

	/** 캐리포워드로 fb가 이미 실린 행은 역전파 대상이 아니다 — 이중 가산 금지. */
	@Test
	void 역전파는_fb가_이미_있는_행을_건드리지_않는다() {
		snapshots.upsertPost(LocalDate.of(2026, 8, 1), reels(100L, 40L));    // 관측
		snapshots.upsertPost(LocalDate.of(2026, 8, 2), reels(110L, null));   // 캐리포워드 40
		snapshots.upsertPost(LocalDate.of(2026, 8, 3), reels(120L, 45L));    // 새 관측 45
		assertThat(db.queryForMap("SELECT views, fb_plays FROM post_snapshot WHERE captured_on='2026-08-01'"))
				.containsEntry("views", 140L).containsEntry("fb_plays", 40L);   // 45로 덮이지 않는다
		assertThat(db.queryForMap("SELECT views, fb_plays FROM post_snapshot WHERE captured_on='2026-08-02'"))
				.containsEntry("views", 150L).containsEntry("fb_plays", 40L);
	}

	/** views가 null인 행(피드·보강 실패)은 역전파로도 만들어내지 않는다. */
	@Test
	void 역전파는_views_null_행을_건드리지_않는다() {
		snapshots.upsertPost(LocalDate.of(2026, 8, 1), reels(null, null));
		snapshots.upsertPost(LocalDate.of(2026, 8, 2), reels(120L, 40L));
		assertThat(db.queryForMap("SELECT views, fb_plays FROM post_snapshot WHERE captured_on='2026-08-01'"))
				.containsEntry("views", null).containsEntry("fb_plays", null);
	}

	@Test
	void fb를_한번도_관측못하면_views는_IG몫_그대로다() {
		snapshots.upsertPost(LocalDate.of(2026, 8, 1), reels(100L, null));
		assertThat(db.queryForMap("SELECT views, fb_plays FROM post_snapshot WHERE captured_on='2026-08-01'"))
				.containsEntry("views", 100L).containsEntry("fb_plays", null);
	}

	@Test
	void 조회수_null이면_fb가_있어도_views는_null이다() {
		// 피드·클립 보강 실패 — views 부재는 fb와 무관하게 그대로 null(비공개 오탐 방지 계약 유지)
		snapshots.upsertPost(LocalDate.of(2026, 8, 1), reels(null, 40L));
		assertThat(db.queryForMap("SELECT views, fb_plays FROM post_snapshot WHERE captured_on='2026-08-01'"))
				.containsEntry("views", null).containsEntry("fb_plays", 40L);
	}

	@Test
	void 프로필_스냅샷도_일_1회_upsert() {
		snapshots.upsertProfile("acct_a", LocalDate.of(2026, 7, 28),
				new ProfileInfo("acct_a", "1", 100L, 10L, 5L, "이름", "https://img", null, null, null));
		snapshots.upsertProfile("acct_a", LocalDate.of(2026, 7, 28),
				new ProfileInfo("acct_a", "1", 120L, 11L, 6L, "이름", "https://img", null, null, null));
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

	/** Hiker 업스트림이 "exception://" 같은 무효 스킴을 주면 null로 저장된다(결함 ②). */
	@Test
	void 무효_스킴_profile_image_url은_null로_저장된다() {
		profileMeta.upsert("acct_a", "표시이름", "exception://", LocalDate.of(2026, 7, 20));

		var row = db.queryForMap("SELECT * FROM profile_meta WHERE username='acct_a'");
		assertThat(row.get("display_name")).isEqualTo("표시이름");
		assertThat(row.get("profile_image_url")).isNull();
	}

	/** 기존에 유효한 값이 있는 행에 무효 스킴이 오면 정규화 결과(null)로 덮지 않고 기존 값을 보존한다. */
	@Test
	void 무효_스킴이_와도_기존_유효_profile_image_url을_보존한다() {
		profileMeta.upsert("acct_a", "이름", "https://img/1.jpg", LocalDate.of(2026, 7, 20));

		profileMeta.upsert("acct_a", "새이름", "exception://", LocalDate.of(2026, 7, 25));

		var row = db.queryForMap("SELECT * FROM profile_meta WHERE username='acct_a'");
		assertThat(row.get("display_name")).isEqualTo("새이름");
		assertThat(row.get("profile_image_url")).isEqualTo("https://img/1.jpg");
	}

	// ── profile_meta POST 등록분(트랙 II) ─────────────────────────────────────

	@Test
	void upsertOwnerFromPost는_행이_없으면_신규_생성한다() {
		profileMeta.upsertOwnerFromPost("acct_a", "표시이름", "https://img/owner.jpg");

		var row = db.queryForMap("SELECT * FROM profile_meta WHERE username='acct_a'");
		assertThat(row.get("display_name")).isEqualTo("표시이름");
		assertThat(row.get("profile_image_url")).isEqualTo("https://img/owner.jpg");
		assertThat(row.get("last_uploaded_at")).isNull();   // 단건 경로는 최근 게시일을 알 수 없다
	}

	/** 계정 갈래(upsert)가 채운 last_uploaded_at을 POST 갈래(upsertOwnerFromPost)가 건드리면 안 된다. */
	@Test
	void upsertOwnerFromPost는_기존_last_uploaded_at을_보존한다() {
		profileMeta.upsert("acct_a", "계정갈래이름", "https://img/account.jpg", LocalDate.of(2026, 7, 20));

		profileMeta.upsertOwnerFromPost("acct_a", "표시이름", "https://img/owner.jpg");

		assertThat(db.queryForObject(
				"SELECT last_uploaded_at FROM profile_meta WHERE username='acct_a'", LocalDate.class))
				.isEqualTo(LocalDate.of(2026, 7, 20));
	}

	/** 단건 응답 셰이프에 owner 필드가 없어 null이 들어와도 기존 display_name·profile_image_url을 지우면 안 된다. */
	@Test
	void upsertOwnerFromPost는_null_인자로_기존_값을_지우지_않는다() {
		profileMeta.upsertOwnerFromPost("acct_a", "표시이름", "https://img/owner.jpg");

		profileMeta.upsertOwnerFromPost("acct_a", null, null);

		var row = db.queryForMap("SELECT * FROM profile_meta WHERE username='acct_a'");
		assertThat(row.get("display_name")).isEqualTo("표시이름");
		assertThat(row.get("profile_image_url")).isEqualTo("https://img/owner.jpg");
	}

	/** upsertOwnerFromPost 경로도 무효 스킴을 걸러낸다(결함 ②) — 계정 갈래(upsert)와 동일 규칙. */
	@Test
	void upsertOwnerFromPost도_무효_스킴을_걸러낸다() {
		profileMeta.upsertOwnerFromPost("acct_a", "표시이름", "exception://");

		var row = db.queryForMap("SELECT * FROM profile_meta WHERE username='acct_a'");
		assertThat(row.get("display_name")).isEqualTo("표시이름");
		assertThat(row.get("profile_image_url")).isNull();
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

	/** profile_meta 결함 ②와 동형(트랙 KK 확장) — Hiker 업스트림이 무효 스킴을 주면 null로 저장된다. */
	@Test
	void 무효_스킴_thumbnail_url은_null로_저장된다() {
		postMeta.upsert("SC1", "acct_a", "REELS", LocalDate.of(2026, 7, 28), "캡션", "exception://");

		var row = db.queryForMap("SELECT * FROM post_meta WHERE short_code='SC1'");
		assertThat(row.get("caption")).isEqualTo("캡션");
		assertThat(row.get("thumbnail_url")).isNull();
	}

	/** 기존에 유효한 썸네일이 있는 행에 무효 스킴이 오면 정규화 결과(null)로 덮지 않고 기존 값을 보존한다. */
	@Test
	void 무효_스킴이_와도_기존_유효_thumbnail_url을_보존한다() {
		postMeta.upsert("SC1", "acct_a", "REELS", LocalDate.of(2026, 7, 28), "캡션", "https://cdn/thumb1.jpg");

		postMeta.upsert("SC1", "acct_a", "REELS", LocalDate.of(2026, 7, 29), "캡션 갱신", "exception://");

		var row = db.queryForMap("SELECT * FROM post_meta WHERE short_code='SC1'");
		assertThat(row.get("caption")).isEqualTo("캡션 갱신");
		assertThat(row.get("thumbnail_url")).isEqualTo("https://cdn/thumb1.jpg");
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

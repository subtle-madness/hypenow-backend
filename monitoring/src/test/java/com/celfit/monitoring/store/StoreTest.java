package com.celfit.monitoring.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.domain.CandidateStatus;
import com.celfit.monitoring.domain.KeywordRule;
import com.celfit.monitoring.domain.TargetStatus;
import com.celfit.monitoring.domain.TargetType;
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

	@BeforeEach
	void setUp() {
		var ds = TestDb.dataSource(TestDb.container());
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		targets = new TargetRepository(db);
		candidates = new CandidateRepository(db);
		snapshots = new SnapshotRepository(db);
		rawPayloads = new RawPayloadRepository(db);
	}

	@Test
	void 등록키_멱등_조회와_keyword_rule_왕복() {
		var rule = new KeywordRule(List.of("샤넬"), List.of(), List.of("이벤트"));
		long id = targets.insert(TargetType.ACCOUNT, "acct_a", null, rule,
				TargetStatus.WATCHING, null, "key-1", Instant.parse("2026-08-28T00:00:00Z"));
		var found = targets.findByRegistrationKey("key-1").orElseThrow();
		assertThat(found.id()).isEqualTo(id);
		assertThat(found.keywordRule().and()).containsExactly("샤넬");
		assertThat(found.keywordRule().exclude()).containsExactly("이벤트");
		assertThat(found.status()).isEqualTo(TargetStatus.WATCHING);
	}

	@Test
	void 같은_후보는_한_번만_생성() {
		long id = targets.insert(TargetType.ACCOUNT, "acct_a", null,
				new KeywordRule(List.of("샤넬"), List.of(), List.of()),
				TargetStatus.WATCHING, null, "key-2", Instant.now().plusSeconds(3600));
		candidates.insertPending(id, "SC1", "…샤넬…");
		candidates.insertPending(id, "SC1", "…샤넬…");
		assertThat(db.queryForObject("SELECT count(*) FROM detected_candidate", Long.class)).isEqualTo(1);
	}

	@Test
	void 후보_상태_전이() {
		long targetId = targets.insert(TargetType.ACCOUNT, "acct_a", null,
				new KeywordRule(List.of("샤넬"), List.of(), List.of()),
				TargetStatus.WATCHING, null, "key-4", Instant.now().plusSeconds(3600));
		candidates.insertPending(targetId, "SC9", "…샤넬…");
		long candidateId = db.queryForObject("SELECT id FROM detected_candidate WHERE short_code='SC9'", Long.class);

		var pending = candidates.find(candidateId).orElseThrow();
		assertThat(pending.targetId()).isEqualTo(targetId);
		assertThat(pending.status()).isEqualTo(CandidateStatus.PENDING);

		candidates.setStatus(candidateId, CandidateStatus.APPROVED);
		assertThat(candidates.find(candidateId).orElseThrow().status()).isEqualTo(CandidateStatus.APPROVED);
	}

	@Test
	void 스냅샷은_일_1회_upsert() {
		var post = new PostInfo("SC1", "acct_a", "REELS", "캡션", 1753670000L, 10L, 2L, 100L, null, null, null, "{}");
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), post);
		var post2 = new PostInfo("SC1", "acct_a", "REELS", "캡션", 1753670000L, 12L, 3L, 110L, null, null, null, "{}");
		snapshots.upsertPost(LocalDate.of(2026, 7, 28), post2);
		assertThat(db.queryForObject(
				"SELECT likes FROM post_snapshot WHERE short_code='SC1'", Long.class)).isEqualTo(12);
	}

	@Test
	void 프로필_스냅샷도_일_1회_upsert() {
		snapshots.upsertProfile("acct_a", LocalDate.of(2026, 7, 28),
				new ProfileInfo("acct_a", "1", 100L, 10L, 5L, "{}"));
		snapshots.upsertProfile("acct_a", LocalDate.of(2026, 7, 28),
				new ProfileInfo("acct_a", "1", 120L, 11L, 6L, "{}"));
		assertThat(db.queryForObject("SELECT count(*) FROM profile_snapshot", Long.class)).isEqualTo(1);
		assertThat(db.queryForObject(
				"SELECT followers FROM profile_snapshot WHERE username='acct_a'", Long.class)).isEqualTo(120);
	}

	@Test
	void 만료_스윕은_활성만_EXPIRED로() {
		targets.insert(TargetType.POST, "acct_a", "SC1", null,
				TargetStatus.TRACKING, "SC1", "key-3", Instant.now().minusSeconds(60));
		int expired = targets.expireOverdue();
		assertThat(expired).isEqualTo(1);
		assertThat(targets.findActive()).isEmpty();
	}

	@Test
	void 원형_응답은_jsonb로_적재된다() {
		rawPayloads.save("PROFILE", "acct_a", 200, "{\"username\":\"acct_a\"}");
		assertThat(db.queryForObject("""
				SELECT payload ->> 'username' FROM raw.fetch_payload
				WHERE kind='PROFILE' AND subject='acct_a' AND http_status=200""", String.class))
				.isEqualTo("acct_a");
	}
}

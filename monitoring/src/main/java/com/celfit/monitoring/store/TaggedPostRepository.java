package com.celfit.monitoring.store;

import com.celfit.monitoring.hiker.PostInfo;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * brand_tagged_post 접점 — 브랜드 윈도우의 게시물 링크 + 댓글 게이트 상태.
 * 지표·메타·댓글 본문은 기존 공용 테이블(post_snapshot·post_meta·post_comment)에 있다.
 */
@Repository
public class TaggedPostRepository {

	private final JdbcTemplate db;

	public TaggedPostRepository(JdbcTemplate db) {
		this.db = db;
	}

	/** 이 브랜드가 확보한 전체 code — 감지 신규 판정용(윈도우 이탈분 포함: 재유입 시 신규 아님). */
	public Set<String> knownCodes(long brandId) {
		return new HashSet<>(db.queryForList(
				"SELECT short_code FROM brand_tagged_post WHERE brand_id = ?", String.class, brandId));
	}

	/**
	 * 브랜드의 <b>태그 열거 산지</b> 행 수 — 확장 스킵 판정 입력(스펙 §7-2). 상한
	 * (collection-post-limit) 이상이면 재백필이 기지 게시물만 세다 컷되므로 확장은 창·커버리지
	 * 마킹만 하고 백필을 제출하지 않는다.
	 *
	 * <p><b>tag_detected_at IS NOT NULL 가드 필수</b>: 상한은 태그 열거를 지배하고 direct 등록
	 * 게시물은 상한 밖이다(스펙 §7-3). 순수 direct 행까지 세면 태그 1,900 + direct 150 같은
	 * 브랜드가 상한 미달인데도 확장 스킵으로 걸려, 확장 구간에서 받을 수 있었던 잔여분
	 * (limit - 태그행수)을 영영 못 받는다.
	 */
	public long countByBrand(long brandId) {
		return db.queryForObject(
				"SELECT count(*) FROM brand_tagged_post WHERE brand_id = ? AND tag_detected_at IS NOT NULL",
				Long.class, brandId);
	}

	/**
	 * 신규 감지 게시물 링크 — 재감지(ON CONFLICT)는 지표·메타를 건드리지 않는다. taken_at null은
	 * 호출자가 거른다.
	 *
	 * <p>tag_detected_at을 명시적으로 now()로 채우고, 이미 있던 행(direct로 먼저 들어온 행)을 열거가
	 * 나중에 만나면 COALESCE로 그 값을 채운다(2026-08-18 direct 통합 §5-3) — DO NOTHING으로 두면
	 * direct-only 행의 tag_detected_at이 영영 안 채워져, 다음 열거가 이 게시물을 또 direct-only로
	 * 취급해 열거와 2단계 단건 콜이 같은 게시물을 이중 수집한다.
	 */
	public void insert(long brandId, PostInfo post) {
		db.update("""
				INSERT INTO brand_tagged_post (brand_id, short_code, author_username, author_ig_user_id, taken_at, tag_detected_at)
				VALUES (?, ?, ?, ?, ?, now())
				ON CONFLICT (brand_id, short_code) DO UPDATE SET
					tag_detected_at = COALESCE(brand_tagged_post.tag_detected_at, now())""",
				brandId, post.shortCode(), post.username(), post.ownerUserId(),
				Timestamp.from(Instant.ofEpochSecond(post.takenAt())));
	}

	/**
	 * direct 등록 upsert(2026-08-18 direct 통합 §2-2) — 단건 콜로 얻은 게시물을 direct 표식과 함께
	 * 링크한다. tag_detected_at은 NULL로 둬 DEFAULT now()를 무력화한다(이 행이 열거 산지가 아니라는
	 * 표시 — 태그 열거가 나중에 이 게시물을 만나면 {@link #insert}가 COALESCE로 채운다).
	 * 이미 tagged로 있던 행을 direct로 등록하면(겹침) tag_detected_at은 건드리지 않고
	 * direct_registered_at만 얹는다 — COALESCE라 재등록·재수집으로 최초 등록 시각이 밀리지 않는다.
	 */
	public void upsertDirect(long brandId, PostInfo post, Instant registeredAt) {
		db.update("""
				INSERT INTO brand_tagged_post
				    (brand_id, short_code, author_username, author_ig_user_id, taken_at,
				     tag_detected_at, direct_registered_at)
				VALUES (?, ?, ?, ?, ?, NULL, ?)
				ON CONFLICT (brand_id, short_code) DO UPDATE SET
				    direct_registered_at = COALESCE(brand_tagged_post.direct_registered_at, EXCLUDED.direct_registered_at),
				    author_ig_user_id    = COALESCE(brand_tagged_post.author_ig_user_id, EXCLUDED.author_ig_user_id)""",
				brandId, post.shortCode(), post.username(), post.ownerUserId(),
				Timestamp.from(Instant.ofEpochSecond(post.takenAt())), Timestamp.from(registeredAt));
	}

	/** 취소(겹침 행) — direct 표식만 해제, tagged 행은 그대로 남는다(설계 §2-4). 행이 없어도 무해. */
	public void clearDirect(long brandId, String shortCode) {
		db.update("UPDATE brand_tagged_post SET direct_registered_at = NULL WHERE brand_id = ? AND short_code = ?",
				brandId, shortCode);
	}

	/**
	 * 취소(순수 direct 행) — tag_detected_at이 없는(=태그 열거가 한 번도 만난 적 없는) 행만 지운다.
	 * 겹침 행을 잘못 지우면 태그 발견 사실이 사라진다. @return 실제로 지웠으면 true.
	 */
	public boolean deleteIfDirectOnly(long brandId, String shortCode) {
		return db.update(
				"DELETE FROM brand_tagged_post WHERE brand_id = ? AND short_code = ? AND tag_detected_at IS NULL",
				brandId, shortCode) > 0;
	}

	/** direct 등록 API 응답 재구성용(멱등 200) — direct_registered_at이 있는 행만 반환. */
	public record DirectSnapshot(String shortCode, String authorUsername, Instant takenAt, String contentType) {}

	/**
	 * 이미 direct 등록된 행 조회 — POST 등록 API가 재요청(멱등)인지 판별하는 데 쓴다. content_type은
	 * brand_post_meta(게시물 전역 표시 메타)에서 함께 읽는다 — 응답 셰이프가 두 테이블에 걸쳐 있어서다.
	 */
	public Optional<DirectSnapshot> findDirectSnapshot(long brandId, String shortCode) {
		return db.query("""
				SELECT tp.short_code, tp.author_username, tp.taken_at, pm.content_type
				FROM brand_tagged_post tp
				LEFT JOIN brand_post_meta pm ON pm.short_code = tp.short_code
				WHERE tp.brand_id = ? AND tp.short_code = ? AND tp.direct_registered_at IS NOT NULL""",
				(rs, i) -> new DirectSnapshot(rs.getString("short_code"), rs.getString("author_username"),
						rs.getTimestamp("taken_at").toInstant(), rs.getString("content_type")),
				brandId, shortCode).stream().findFirst();
	}

	/** 댓글 게이트 저장값 배치 조회(IN절 1쿼리) — 열거 comment_count가 이 값보다 클 때만 댓글 콜. */
	public Map<String, Long> commentsCollectedCounts(long brandId, Collection<String> codes) {
		if (codes.isEmpty()) {
			return Map.of();
		}
		String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
		Object[] args = new Object[codes.size() + 1];
		args[0] = brandId;
		int i = 1;
		for (String code : codes) {
			args[i++] = code;
		}
		Map<String, Long> out = new HashMap<>();
		db.query("SELECT short_code, comments_collected_count FROM brand_tagged_post WHERE brand_id = ? AND short_code IN ("
						+ placeholders + ")",
				rs -> {
					out.put(rs.getString("short_code"), rs.getLong("comments_collected_count"));
				}, args);
		return out;
	}

	public void updateCommentsCollected(long brandId, String shortCode, long count) {
		db.update("""
				UPDATE brand_tagged_post SET comments_collected_count = ?
				WHERE brand_id = ? AND short_code = ?""", count, brandId, shortCode);
	}

	/** 티어 판정 입력 행 — 판정 자체는 BrandCrawlPolicy 순수 함수가 한다(스펙 §3). */
	public record TrackedPost(String shortCode, Instant takenAt, Instant lastCrawledAt) {}

	/**
	 * 추적 범위(taken_at ≥ minTakenAt) 링크 전부 — 스윕의 열거 깊이 결정 입력(스펙 §4).
	 *
	 * <p><b>tag_detected_at IS NOT NULL 가드 필수</b>(2026-08-18 direct 통합 §5-3): direct-only
	 * 행(태그 열거가 한 번도 만난 적 없는 행)은 이 목록에 절대 나타나면 안 된다 — 나타나면 due로
	 * 잡혀 열거 깊이를 그 taken_at까지 끌어내리는데, 열거는 애초에 그 게시물을 만날 수 없으니
	 * {@code touchCrawled}가 영영 안 걸려 매일 최대 180일 깊이를 여는 요청량 누수가 영구화된다.
	 */
	public List<TrackedPost> trackedPosts(long brandId, Instant minTakenAt) {
		return db.query("""
				SELECT short_code, taken_at, last_crawled_at FROM brand_tagged_post
				WHERE brand_id = ? AND taken_at >= ? AND tag_detected_at IS NOT NULL""",
				(rs, i) -> {
					Timestamp last = rs.getTimestamp("last_crawled_at");
					return new TrackedPost(rs.getString("short_code"),
							rs.getTimestamp("taken_at").toInstant(),
							last == null ? null : last.toInstant());
				}, brandId, Timestamp.from(minTakenAt));
	}

	/**
	 * direct 2단계 스윕의 모수(2026-08-18 direct 통합 §5-3) — direct 등록됐지만 태그 열거가 한 번도
	 * 만난 적 없는(tag_detected_at NULL) 행만. 겹침 행(둘 다 있음)은 1단계 태그 열거가 이미 커버하므로
	 * 여기서 빠진다(중복 콜 없음). due 판정 자체는 호출자가 {@link BrandCrawlPolicy#due}로 한다.
	 */
	public List<TrackedPost> directDuePosts(long brandId, Instant minTakenAt) {
		return db.query("""
				SELECT short_code, taken_at, last_crawled_at FROM brand_tagged_post
				WHERE brand_id = ? AND direct_registered_at IS NOT NULL
				  AND tag_detected_at IS NULL AND taken_at >= ?""",
				(rs, i) -> {
					Timestamp last = rs.getTimestamp("last_crawled_at");
					return new TrackedPost(rs.getString("short_code"),
							rs.getTimestamp("taken_at").toInstant(),
							last == null ? null : last.toInstant());
				}, brandId, Timestamp.from(minTakenAt));
	}

	/**
	 * 자연 종료된 열거가 커버한 깊이 전체를 갱신 — 열거에서 사라진 링크가 깊이 컷을 영구 고정하는
	 * 것을 막는다. last_crawled_at의 의미는 "이 게시물을 봤다"가 아니라 <b>"이 깊이까지 커버했다"</b>다:
	 * 삭제·태그 제거·비공개 전환으로 열거에 더 안 실리는 링크는 {@link #touchCrawled}로는 영영
	 * 갱신되지 않아 due가 영구 true로 굳고, 매 스윕이 그 taken_at까지 깊이를 여는 요청량 누수가 된다.
	 * 호출은 열거가 자연 종료(페이지 전체가 컷 이전·커서 소진)했을 때만 — 안전 상한·커서 미전진으로
	 * 끊긴 스윕은 깊이를 커버하지 못했으므로 갱신하지 않는다(다음 스윕의 자가 치유 유지).
	 *
	 * <p><b>tag_detected_at IS NOT NULL 가드 필수</b>(2026-08-18 direct 통합 §5-3): 이 가드가 없으면
	 * direct-only 행이 "수집된 적 없는데 크롤됨"으로 마킹돼(태그 열거가 커버 깊이 전체를 이 행까지
	 * touch해 버려서) 2단계 단건 수집(due 판정)이 영영 안 돈다.
	 */
	public void touchCrawledDepth(long brandId, Instant minTakenAt, Instant at) {
		db.update("""
				UPDATE brand_tagged_post SET last_crawled_at = ?
				WHERE brand_id = ? AND taken_at >= ? AND tag_detected_at IS NOT NULL""",
				Timestamp.from(at), brandId, Timestamp.from(minTakenAt));
	}

	/** 이번 열거에서 만난 게시물의 마지막 수집 시각 배치 갱신 — 다음 스윕의 티어 판정 입력. */
	public void touchCrawled(long brandId, Collection<String> codes, Instant at) {
		if (codes.isEmpty()) {
			return;
		}
		String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
		Object[] args = new Object[codes.size() + 2];
		args[0] = Timestamp.from(at);
		args[1] = brandId;
		int i = 2;
		for (String code : codes) {
			args[i++] = code;
		}
		db.update("UPDATE brand_tagged_post SET last_crawled_at = ? WHERE brand_id = ? AND short_code IN ("
				+ placeholders + ")", args);
	}

	/**
	 * 보강 정산 마킹(2026-08-13 스펙 §1) — 성공이든 재시도 소진이든 "더 기다릴 이유가 없어진"
	 * 게시물에 찍는다. was 목록 게이트의 정본이다. 재마킹은 무해하다(같은 게시물을 다음 스윕이
	 * 다시 보강하면 시각만 갱신 — 노출 여부는 안 바뀐다).
	 */
	public void markEnriched(long brandId, Collection<String> codes, Instant at) {
		if (codes.isEmpty()) {
			return;
		}
		String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));
		Object[] args = new Object[codes.size() + 2];
		args[0] = Timestamp.from(at);
		args[1] = brandId;
		int i = 2;
		for (String code : codes) {
			args[i++] = code;
		}
		db.update("UPDATE brand_tagged_post SET enriched_at = ? WHERE brand_id = ? AND short_code IN ("
				+ placeholders + ")", args);
	}
}

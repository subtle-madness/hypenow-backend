package com.celfit.monitoring.store;

import com.celfit.instagram.source.PostInfo;
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
	 * n번째 최신 <b>태그 열거 산지</b> 행의 taken_at — 확장 스킵 판정 입력(스펙 §7-2 개정).
	 * n = 수집 개수 상한이면 이 값이 곧 <b>"지금 재백필하면 컷이 어디서 걸리나"의 정확한 예측치</b>다
	 * (열거는 최신부터 단방향이라 limit번째 게시물에서 끊긴다). 행이 n개 미만이면 empty —
	 * 컷이 걸리지 않는다는 뜻이다.
	 *
	 * <p><b>행 수(count)로 판정하면 안 된다</b>(구 countByBrand의 결함): 생애 누적 행 수는 창 밖
	 * 과거분까지 세므로, 10건/일·3개월 창·8개월 운영 브랜드(누적 2,400 / 창 안 900)를 재백필이
	 * 컷되지 않을 것인데도 capped로 오표기한다. 그 마킹은 §7-4 컷 클램프까지 걸어 도달 가능했던
	 * 90~180일 구간을 동결시키고 was에 거짓 커버리지를 내려보낸다.
	 *
	 * <p><b>tag_detected_at IS NOT NULL 가드 필수</b>: 상한은 태그 열거를 지배하고 direct 등록
	 * 게시물은 상한 밖이다(스펙 §7-3). 순수 direct 행까지 세면 태그 1,900 + direct 150 같은
	 * 브랜드가 상한 미달인데도 확장 스킵으로 걸려, 확장 구간에서 받을 수 있었던 잔여분
	 * (limit - 태그행수)을 영영 못 받는다.
	 */
	public Optional<Instant> nthNewestTagTakenAt(long brandId, int n) {
		if (n <= 0) {
			return Optional.empty();
		}
		return db.query("""
				SELECT taken_at FROM brand_tagged_post
				WHERE brand_id = ? AND tag_detected_at IS NOT NULL
				ORDER BY taken_at DESC
				OFFSET ? LIMIT 1""",
				(rs, i) -> rs.getTimestamp("taken_at").toInstant(), brandId, n - 1)
				.stream().findFirst();
	}

	/**
	 * 해시태그 감시 세트의 바닥(2026-09-02 감시 세트 2,000 설계 §1) — hashtag 성분 행 중 게시일
	 * n번째 최신의 taken_at. 행이 n개 미만이면(세트 미포화) empty — 이때는 바닥이 없다.
	 * {@link #nthNewestTagTakenAt}의 hashtag판(같은 OFFSET 관용구).
	 */
	public Optional<Instant> nthNewestHashtagTakenAt(long brandId, int n) {
		if (n <= 0) {
			return Optional.empty();
		}
		return db.query("""
				SELECT taken_at FROM brand_tagged_post
				WHERE brand_id = ? AND hashtag_detected_at IS NOT NULL
				ORDER BY taken_at DESC
				OFFSET ? LIMIT 1""",
				(rs, i) -> rs.getTimestamp("taken_at").toInstant(), brandId, n - 1)
				.stream().findFirst();
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

	/**
	 * 해시태그 편입 upsert(2026-08-27 해시태그 직접 수집 설계 §2-3) — 해시태그 recent 열거로 얻은
	 * 게시물을 hashtag 표식과 함께 통합 풀에 링크한다. {@link #upsertDirect}와 같은 규칙이다:
	 * tag_detected_at은 명시적 NULL로 둬 DEFAULT now()를 무력화하고(이 행이 태그 열거 산지가
	 * 아니라는 표시 — 나중에 태그 열거가 만나면 {@link #insert}가 COALESCE로 채운다), 이미 있던
	 * 행(tagged·direct)에는 hashtag_detected_at만 얹는다. COALESCE라 재발견·재수집으로 최초 편입
	 * 시각이 밀리지 않는다.
	 */
	public void upsertHashtag(long brandId, PostInfo post, Instant detectedAt) {
		db.update("""
				INSERT INTO brand_tagged_post
				    (brand_id, short_code, author_username, author_ig_user_id, taken_at,
				     tag_detected_at, hashtag_detected_at)
				VALUES (?, ?, ?, ?, ?, NULL, ?)
				ON CONFLICT (brand_id, short_code) DO UPDATE SET
				    hashtag_detected_at = COALESCE(brand_tagged_post.hashtag_detected_at, EXCLUDED.hashtag_detected_at),
				    author_ig_user_id   = COALESCE(brand_tagged_post.author_ig_user_id, EXCLUDED.author_ig_user_id)""",
				brandId, post.shortCode(), post.username(), post.ownerUserId(),
				Timestamp.from(Instant.ofEpochSecond(post.takenAt())), Timestamp.from(detectedAt));
	}

	/**
	 * 이 브랜드에서 hashtag 성분이 이미 있는 코드 전체 — 해시태그 스윕의 dedup·조기 종료 판정과
	 * 감시 세트 예산 계산(크기, 2026-09-02 감시 세트 2,000 설계 §2 — 구 편입 상한 잔량 계산의
	 * 후신)의 공용 입력이다(구 {@code BrandHashtagRepository.existingCodes}의 통합 풀판). 스윕 1회당
	 * 1번만 읽고 페이지마다 메모리로 교차한다 — 페이지당 IN 쿼리보다 싸다.
	 *
	 * <p>기준이 "브랜드 풀에 있는 코드"가 아니라 "hashtag 성분이 있는 코드"인 것이 핵심이다:
	 * 전자로 하면 tagged 열거가 이미 확보한 게시물이 전부 조기 종료 신호가 돼, 해시태그 스트림
	 * 깊은 곳의 hashtag-only 게시물에 영영 도달하지 못한다.
	 */
	public Set<String> hashtagCodes(long brandId) {
		return new HashSet<>(db.queryForList(
				"SELECT short_code FROM brand_tagged_post WHERE brand_id = ? AND hashtag_detected_at IS NOT NULL",
				String.class, brandId));
	}

	/**
	 * 매칭 태그 기록(2026-08-27 설계 §1) — "이 (brand, shortcode)가 이 태그의 열거 스트림에도
	 * 나타났다". FK가 brand_tagged_post를 향하므로 호출부는 편입 직후(또는 이미 있는 행)에만
	 * 부른다. 멱등(ON CONFLICT DO NOTHING).
	 */
	public void recordMatchedTag(long brandId, String shortCode, String tag) {
		db.update("""
				INSERT INTO brand_post_matched_tag (brand_id, short_code, tag)
				VALUES (?, ?, ?)
				ON CONFLICT DO NOTHING""",
				brandId, shortCode, tag);
	}

	/** {@link #recordMatchedTag} 배치판 — 페이지 내 "이미 hashtag 성분이 있는" 코드 묶음 전용. */
	public void recordMatchedTags(long brandId, Collection<String> shortCodes, String tag) {
		for (String shortCode : shortCodes) {
			recordMatchedTag(brandId, shortCode, tag);
		}
	}

	/** 취소(겹침 행) — direct 표식만 해제, tagged 행은 그대로 남는다(설계 §2-4). 행이 없어도 무해. */
	public void clearDirect(long brandId, String shortCode) {
		db.update("UPDATE brand_tagged_post SET direct_registered_at = NULL WHERE brand_id = ? AND short_code = ?",
				brandId, shortCode);
	}

	/**
	 * 취소(순수 direct 행) — tag_detected_at이 없는(=태그 열거가 한 번도 만난 적 없는) 행만 지운다.
	 * tagged나 hashtag 성분이 있으면 삭제하지 않는다(2026-08-27 해시태그 직접 수집 설계 §2-4) —
	 * 겹침 행을 잘못 지우면 태그·해시태그 발견 사실은 물론 {@code brand_post_matched_tag} 매칭 태그
	 * 행까지 CASCADE로 함께 사라진다. @return 실제로 지웠으면 true.
	 */
	public boolean deleteIfDirectOnly(long brandId, String shortCode) {
		return db.update(
				"DELETE FROM brand_tagged_post WHERE brand_id = ? AND short_code = ?"
						+ " AND tag_detected_at IS NULL AND hashtag_detected_at IS NULL",
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
	 * 추적 범위(taken_at ≥ minTakenAt)의 <b>순수 태그 행</b> — 스윕의 열거 깊이 결정 입력(스펙 §4).
	 *
	 * <p><b>tag_detected_at IS NOT NULL 가드 필수</b>(2026-08-18 direct 통합 §5-3): direct-only
	 * 행(태그 열거가 한 번도 만난 적 없는 행)은 이 목록에 절대 나타나면 안 된다 — 나타나면 due로
	 * 잡혀 열거 깊이를 그 taken_at까지 끌어내리는데, 열거는 애초에 그 게시물을 만날 수 없으니
	 * {@code touchCrawled}가 영영 안 걸려 매일 최대 180일 깊이를 여는 요청량 누수가 영구화된다.
	 *
	 * <p><b>direct_registered_at IS NULL 가드도 필수</b>(2026-08-19 수집 상한 v2 §7-3 — 같은 원리의
	 * 확장): direct 등록 게시물은 상한 밖이라 {@link #touchCrawledDepth}의 커버 간주 touch를 받지
	 * 않는다. 그래서 컷 밖 겹침 행(태그·direct 둘 다인 행)의 due는 1단계 열거가 도달하지 못하는
	 * 한 잔존하는데, 그걸 여기 남겨 두면 매 스윕이 도달 불가 깊이까지 열거를 벌린다. 이 행들의
	 * 갱신은 열거 깊이가 아니라 {@link #unenumeratedDuePosts} 2단계 단건 콜이 책임진다 — 추가 비용은
	 * "컷 밖 direct 게시물 수 × 티어 주기당 1콜"로 유한하다.
	 */
	public List<TrackedPost> trackedPosts(long brandId, Instant minTakenAt) {
		return db.query("""
				SELECT short_code, taken_at, last_crawled_at FROM brand_tagged_post
				WHERE brand_id = ? AND taken_at >= ?
				  AND tag_detected_at IS NOT NULL AND direct_registered_at IS NULL
				  AND hashtag_detected_at IS NULL""",
				(rs, i) -> {
					Timestamp last = rs.getTimestamp("last_crawled_at");
					return new TrackedPost(rs.getString("short_code"),
							rs.getTimestamp("taken_at").toInstant(),
							last == null ? null : last.toInstant());
				}, brandId, Timestamp.from(minTakenAt));
	}

	/**
	 * 2단계 스윕의 모수 — <b>브랜드 창 안에서 tagged 열거가 커버하지 못하는 행 전부</b>다
	 * (2026-08-19 수집 상한 v2 §7-3 + <b>2026-08-27 해시태그 직접 수집 설계 §2-5 일반화</b>):
	 * direct 등록 행과 hashtag 편입 행. 겹침 행(태그와 함께 있는 행)도 포함한다: 이 둘은 태그
	 * 열거의 2,000 상한 밖이라, 1단계가 상한에 걸려 도달하지 못한 겹침 행은 여기서 단건 콜로 살려야
	 * 동결되지 않는다. 구 필터({@code tag_detected_at IS NULL})는 그 구제 경로를 막았다.
	 *
	 * <p><b>정렬은 미보강(enriched_at IS NULL) 우선</b>(설계 §5) — 구 감지 데이터 이관분은 게시자·
	 * 댓글·스냅샷이 통째로 비어 있고 was 표시 게이트가 정산분만 서빙하므로, 나이 기반 due 순서에
	 * 맡기면 오래된 이관분의 첫 보강이 한없이 밀린다. 호출부가 스윕당 건수 상한으로 자르므로
	 * 이 정렬이 곧 "누구부터 충전하나"의 정본이다.
	 *
	 * <p><b>중복 콜 방지는 필터가 아니라 구조로 유지된다</b>: 1단계 열거가 실제로 만난 겹침 행은
	 * {@link #touchCrawled}로 last_crawled_at이 갱신돼 호출자의 {@link BrandCrawlPolicy#due}
	 * 판정에서 빠진다. 즉 컷 안이라 방금 수집된 겹침 행은 2단계가 건너뛰고, 컷 밖이라 못 만난
	 * 겹침 행만 단건 수집된다. (예외는 0~14일 티어 — {@code due}가 last_crawled_at과 무관하게 항상
	 * true라 그 나이대 겹침 행은 스윕당 1콜이 겹친다. direct 등록은 수동 소수라 유한한 비용이다.)
	 * 커버 간주 touch({@link #touchCrawledDepth})는 direct·hashtag 행을 건드리지 않으므로 이 due는
	 * 실수집 없이 사라지지 않는다.
	 */
	public List<TrackedPost> unenumeratedDuePosts(long brandId, Instant minTakenAt) {
		return unenumeratedDuePosts(brandId, minTakenAt, null);
	}

	/**
	 * floor판(2026-09-02 감시 세트 2,000 설계 §3) — hashtag 성분 행을 감시 세트 바닥
	 * ({@code hashtagFloor}, {@link #nthNewestHashtagTakenAt}) 이상으로 한정한다. direct 행은
	 * 바닥과 무관하게 항상 모수다(직접 등록은 상한 없음 — 설계 §1). floor가 null이면(세트 미포화)
	 * 기존과 동일하게 전부 돌려준다. <b>세트 밖 행을 여기서 걸러야 하는 이유</b>: 매일 티어(0~14일)
	 * 는 last_crawled_at과 무관하게 매일 due라, 동결 touch만으로는 다음 스윕 모수에서 안 빠진다.
	 */
	public List<TrackedPost> unenumeratedDuePosts(long brandId, Instant minTakenAt, Instant hashtagFloor) {
		Timestamp floor = hashtagFloor == null ? null : Timestamp.from(hashtagFloor);
		return db.query("""
				SELECT short_code, taken_at, last_crawled_at FROM brand_tagged_post
				WHERE brand_id = ?
				  AND (direct_registered_at IS NOT NULL
				       OR (hashtag_detected_at IS NOT NULL AND (?::timestamptz IS NULL OR taken_at >= ?)))
				  AND taken_at >= ?
				ORDER BY (enriched_at IS NULL) DESC, taken_at DESC""",
				(rs, i) -> {
					Timestamp last = rs.getTimestamp("last_crawled_at");
					return new TrackedPost(rs.getString("short_code"),
							rs.getTimestamp("taken_at").toInstant(),
							last == null ? null : last.toInstant());
				}, brandId, floor, floor, Timestamp.from(minTakenAt));
	}

	/**
	 * 감시 세트 밖 해시태그 행 동결 touch(2026-09-02 설계 §3) — tagged의
	 * {@link #touchCrawledDepth}(커버 간주)와 동형: 실수집 없이 last_crawled_at만 갱신해
	 * "이 깊이는 정책상 커버됨(동결 서빙)"으로 기록한다. direct 성분 행은 제외(항상 실수집 대상).
	 * tagged 겹침 행은 포함한다 — tagged의 깊이 touch·trackedPosts는 hashtag 성분 행을 아예
	 * 안 보므로(각 필터의 {@code hashtag_detected_at IS NULL}) 이 행들의 동결은 여기 소관이다.
	 * 되감기 방지 가드로 같은 날 중복 호출·개별 touch와의 경합에도 안전하다.
	 *
	 * <p><b>{@code minTakenAt} 하한 필수</b>(F3, 2026-09-02 최종 리뷰 — {@link #touchCrawledDepth}의
	 * 동형 짝과 같은 유계): 하한이 없으면 이미 추적 창(180일, {@code BrandCrawlPolicy.TRACKED_MAX_AGE})을
	 * 넘어 영구 제외된 행까지 매 스윕마다 이 UPDATE의 스캔·갱신 대상이 돼, 브랜드 나이가 쌓일수록
	 * 대상 범위가 무계로 자란다. 호출부(BrandDirectCollectService.doSweepUnenumerated)가 이미
	 * {@code unenumeratedDuePosts}에 넘기는 것과 같은 값을 그대로 전달한다.
	 */
	public void touchFrozenHashtag(long brandId, Instant minTakenAt, Instant floorTakenAt, Instant at) {
		db.update("""
				UPDATE brand_tagged_post SET last_crawled_at = ?
				WHERE brand_id = ? AND hashtag_detected_at IS NOT NULL AND direct_registered_at IS NULL
				  AND taken_at >= ? AND taken_at < ?
				  AND (last_crawled_at IS NULL OR last_crawled_at < ?)""",
				Timestamp.from(at), brandId, Timestamp.from(minTakenAt), Timestamp.from(floorTakenAt),
				Timestamp.from(at));
	}

	/**
	 * 기동 즉시 백필의 모수(2026-08-28 사용자 지시 — 이관분 점진 충전 대신 즉시 전량) —
	 * {@link #unenumeratedDuePosts}와 같은 population(direct∪hashtag, 브랜드 창 안)에 <b>{@code
	 * enriched_at IS NULL}</b> 필터만 얹는다. 호출자({@code BrandDirectCollectService#backfillUnenriched})는
	 * 이 목록 전체를 상한·due 판정 없이 그대로 소진한다 — 상한(2단계 스윕의 {@code
	 * unenumerated-sweep-limit})과 나이 티어({@link BrandCrawlPolicy#due})는 둘 다 "점진적으로 갚아도
	 * 되는" 정상 운영 전제이고, 기동 백필의 목적은 그 전제를 깨는 일회성 이관 재고를 즉시 소진하는
	 * 것이라 여기서는 적용하지 않는다.
	 *
	 * <p>정렬은 taken_at DESC뿐이다 — {@link #unenumeratedDuePosts}의 "미보강 우선" 보조 정렬은
	 * 이미 enriched_at IS NULL로 걸렀으니 전 행이 미보강이라 의미가 없다.
	 *
	 * <p><b>unavailable_at IS NULL 가드 필수</b>(2026-08-28 리뷰 지적): 삭제·비공개로 확정된
	 * 행({@link #markUnavailable})은 enriched_at을 영영 못 받으므로(재보강 불가) 이 가드가 없으면
	 * 기동 백필이 재기동마다 같은 게시물의 404를 Hiker에 재과금하며 재확인한다. {@link
	 * #unenumeratedDuePosts}(야간 스윕 2단계 모수)는 이 가드가 없다 — 그쪽은 나이 티어 주기로만
	 * 재시도해 비용이 유한하고, 재관측 시 {@link #touchCrawled}가 unavailable_at을 자연 해제하는
	 * 자가 치유 경로가 있다. 기동 백필은 그런 주기적 재시도가 없는 일회성 전량 소진이라 배제해야 한다.
	 */
	public List<TrackedPost> unenrichedUnenumeratedPosts(long brandId, Instant minTakenAt) {
		return db.query("""
				SELECT short_code, taken_at, last_crawled_at FROM brand_tagged_post
				WHERE brand_id = ?
				  AND (direct_registered_at IS NOT NULL OR hashtag_detected_at IS NOT NULL)
				  AND taken_at >= ? AND enriched_at IS NULL AND unavailable_at IS NULL
				ORDER BY taken_at DESC""",
				(rs, i) -> {
					Timestamp last = rs.getTimestamp("last_crawled_at");
					return new TrackedPost(rs.getString("short_code"),
							rs.getTimestamp("taken_at").toInstant(),
							last == null ? null : last.toInstant());
				}, brandId, Timestamp.from(minTakenAt));
	}

	/**
	 * 커버 처리로 끝난 열거가 커버한 깊이 전체를 갱신 — 열거에서 사라진 링크가 깊이 컷을 영구 고정하는
	 * 것을 막는다. last_crawled_at의 의미는 "이 게시물을 봤다"가 아니라 <b>"이 깊이까지 커버했다"</b>다:
	 * 삭제·태그 제거·비공개 전환으로 열거에 더 안 실리는 링크는 {@link #touchCrawled}로는 영영
	 * 갱신되지 않아 due가 영구 true로 굳고, 매 스윕이 그 taken_at까지 깊이를 여는 요청량 누수가 된다.
	 * 호출은 <b>커버 처리로 끝난 종료</b>에서만 — 자연 종료(페이지 전체가 컷 이전·커서 소진)와
	 * <b>수집 개수 상한 컷</b>(2026-08-19 스펙 §3-2 ⑤)이 여기 해당한다. 상한 컷의 깊이는 실제 커버
	 * 깊이가 아니라 목표 컷 전체이고, 컷 밖 게시물의 지표를 마지막 수집 시점으로 굳히는 <b>의도된
	 * 동결</b>이다(due 재열거 루프 차단이 목적 — 그 대가가 동결이다). 반면 안전 밸브·커서 미전진으로
	 * 끊긴 스윕은 깊이를 커버하지 못했으므로 갱신하지 않는다(다음 스윕의 자가 치유 유지).
	 *
	 * <p><b>tag_detected_at IS NOT NULL 가드 필수</b>(2026-08-18 direct 통합 §5-3): 이 가드가 없으면
	 * direct-only 행이 "수집된 적 없는데 크롤됨"으로 마킹돼(태그 열거가 커버 깊이 전체를 이 행까지
	 * touch해 버려서) 2단계 단건 수집(due 판정)이 영영 안 돈다.
	 *
	 * <p><b>direct_registered_at IS NULL 가드도 필수</b>(2026-08-19 수집 상한 v2 §7-3): 위 동결은
	 * 태그 열거 산지 게시물에만 적용되는 비용 정책이고, direct 등록 게시물은 상한 밖이다. 겹침 행
	 * (태그·direct 둘 다)까지 touch하면 컷 밖 direct 게시물의 due가 실크롤 없이 꺼져 {@link
	 * #unenumeratedDuePosts} 2단계 구제 경로가 무력화된다 — 사용자가 직접 등록한 게시물이 조용히
	 * 얼어붙는다. direct 행의 last_crawled_at은 실수집({@link #touchCrawled})으로만 전진한다.
	 */
	public void touchCrawledDepth(long brandId, Instant minTakenAt, Instant at) {
		db.update("""
				UPDATE brand_tagged_post SET last_crawled_at = ?
				WHERE brand_id = ? AND taken_at >= ?
				  AND tag_detected_at IS NOT NULL AND direct_registered_at IS NULL
				  AND hashtag_detected_at IS NULL""",
				Timestamp.from(at), brandId, Timestamp.from(minTakenAt));
	}

	/**
	 * 이번 열거·실수집에서 실제로 만난 게시물의 마지막 수집 시각 배치 갱신 — 다음 스윕의 티어 판정
	 * 입력. 직접 관측 = 존재 확인이므로 삭제·비공개 마킹({@link #markUnavailable})과 부재 검증
	 * 스로틀({@link #markAbsenceChecked})도 여기서 해제한다(IG 보관 후 재공개 자가 치유 + 재소실 시
	 * 즉시 재검증). 깊이 touch({@link #touchCrawledDepth})는 개별 관측이 아니라 해제하지 않는다.
	 */
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
		db.update("UPDATE brand_tagged_post SET last_crawled_at = ?, unavailable_at = NULL,"
				+ " absence_checked_at = NULL WHERE brand_id = ? AND short_code IN (" + placeholders + ")", args);
	}

	/**
	 * 삭제·비공개 관측 마킹(2026-08-25 설계) — 야간 스윕 단건 콜이 404(SubjectNotFound)를 받은
	 * 게시물에 찍는다. 행·스냅샷은 보존(was가 hidden으로 노출), 재관측({@link #touchCrawled})이
	 * 해제하는 자가 치유 짝이다. 재마킹은 무해하다(시각만 갱신).
	 */
	public void markUnavailable(long brandId, String shortCode, Instant at) {
		db.update("UPDATE brand_tagged_post SET unavailable_at = ? WHERE brand_id = ? AND short_code = ?",
				Timestamp.from(at), brandId, shortCode);
	}

	/**
	 * 태그 부재 검증 후보(2026-08-25 tagged 삭제 감지 설계 §1) — 커버된 열거의 검증 하한 안쪽인데
	 * 이번 열거에 안 실렸을 수 있는 tagged-only 행. 겹침 행(direct_registered_at 존재)은 야간 스윕
	 * 2단계 단건 수집이 이미 404를 잡으므로 제외한다(중복 과금 방지). 이미 부재 확정(unavailable_at)
	 * 이거나 최근 검증한(absence_checked_at ≥ recheckBefore — 살아있는 태그 해제 게시물의 재검증
	 * 스로틀) 행도 제외. 이번 열거 관측분 제외는 호출자가 메모리로 거른다(seen이 수천이면 IN 절이
	 * 무의미하게 커진다).
	 */
	public List<String> tagVerifyCandidates(long brandId, Instant minTakenAt, Instant recheckBefore) {
		return db.queryForList("""
				SELECT short_code FROM brand_tagged_post
				WHERE brand_id = ? AND tag_detected_at IS NOT NULL AND direct_registered_at IS NULL
				  AND hashtag_detected_at IS NULL
				  AND taken_at >= ? AND unavailable_at IS NULL
				  AND (absence_checked_at IS NULL OR absence_checked_at < ?)
				ORDER BY taken_at DESC""",
				String.class, brandId, Timestamp.from(minTakenAt), Timestamp.from(recheckBefore));
	}

	/**
	 * 부재 검증 완료 마킹(설계 §2·§3) — 검증 콜이 성공(게시물 생존 — 태그 해제·열거 요동)한 행에
	 * 찍는 재검증 스로틀. 재관측({@link #touchCrawled})이 NULL로 되돌려, 재등장 후 다시 사라지면
	 * 스로틀 없이 즉시 검증한다.
	 */
	public void markAbsenceChecked(long brandId, String shortCode, Instant at) {
		db.update("UPDATE brand_tagged_post SET absence_checked_at = ? WHERE brand_id = ? AND short_code = ?",
				Timestamp.from(at), brandId, shortCode);
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

package com.celfit.was.monitoring;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * monitoring DB의 브랜드 태그 모니터링 테이블 조회(2026-08-07 스펙 §3-2) — brand_account·
 * brand_tagged_post·brand_post_meta·brand_post_snapshot·brand_post_comment·author_profile만 읽는다.
 * 브랜드 파이프라인은 캠페인 테이블(post_meta·post_snapshot 등)과 한 줄도 겹치지 않는 전용
 * 스키마라(08-06 개정), 레거시 테이블·뷰와 조인하지 않는다 — 조합이 필요하면 was 코드에서.
 *
 * <p>주입되는 JdbcClient는 {@link MonitoringConfig}가 내부 생성한 읽기 전용 커넥션이다
 * ({@link MonitoringReadRepository}와 같은 풀). app 스키마·분석 결과와의 크로스 DB 조인 금지.
 *
 * <p>주의: 이 리포지토리는 brandId를 검증 없이 조회한다 — 소유권 스코프는 호출자 책임이며,
 * 반드시 app 매핑(app.brand_monitorings.brand_id)에서 얻은 값만 넘길 것(임의 id 통과 = 남의
 * 브랜드 열람). 명령 계층과 달리 예외를 승격하지 않고 DataAccessException을 원시 전파한다.
 *
 * <p>배치 메서드(findPostMeta·findSnapshots·findComments·findAuthors·findAuthorsByUsername)는
 * "윈도우 안 게시물 전체를 한 SQL 왕복으로" 가져오기 위한 것 — 게시물 수만큼 반복 호출하면 N+1이
 * 된다. 전부 빈 컬렉션을 선처리한다({@code IN ()} 은 SQL 오류).
 */
public class BrandReadRepository {

	private final JdbcClient jdbc;

	public BrandReadRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 브랜드 계정 1행 — followers·biography 등은 매일 스윕이 갱신하는 최신 관측값(추이는
	 * brand_profile_snapshot). backfillCompletedAt null = 최초 백필 미완("수집 준비 중" 판별),
	 * backfillError는 그 실패 사유(스윕 성공 시 monitoring이 클리어).
	 */
	public Optional<BrandAccountRow> findAccount(long brandId) {
		return jdbc.sql("""
				SELECT id, username, last_swept_on, last_swept_at, registered_at, backfill_completed_at,
				       backfill_error, followers, following, media_count, biography, full_name,
				       profile_pic_url, is_verified, external_url, status, image_object_path,
				       collection_months, collection_capped, covered_until,
				       COALESCE(collection_started_at, registered_at) AS collection_started_at
				FROM brand_account
				WHERE id = :brandId
				""")
				.param("brandId", brandId)
				.query(BrandAccountRow.class)
				.optional();
	}

	/**
	 * 브랜드 계정 배치 조회(2026-09-03 어드민 브랜드 목록 계정 API) — {@link #findAccount}와 같은
	 * 컬럼 집합을 여러 브랜드에 대해 한 왕복으로 읽는다(유저별 크롤링 비용 카드의
	 * {@link #findDailyCallCounts}와 같은 위상 — 링크 수만큼 반복 호출하면 N+1이 된다).
	 */
	public List<BrandAccountRow> findAccountsByIds(Collection<Long> brandIds) {
		if (brandIds.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbc.sql("""
				SELECT id, username, last_swept_on, last_swept_at, registered_at, backfill_completed_at,
				       backfill_error, followers, following, media_count, biography, full_name,
				       profile_pic_url, is_verified, external_url, status, image_object_path,
				       collection_months, collection_capped, covered_until,
				       COALESCE(collection_started_at, registered_at) AS collection_started_at
				FROM brand_account
				WHERE id IN (:brandIds)
				""")
				.param("brandIds", brandIds)
				.query(BrandAccountRow.class)
				.list();
	}

	/**
	 * 브랜드별 총 수집량(2026-09-03 어드민 브랜드 목록 계정 API) — tagged·direct·hashtag 전 소스,
	 * 삭제·비공개({@code unavailable_at} 有) 행도 포함한 전체 행수다. 사용자 화면의 기간 창(365일
	 * 컷)·정산(enriched) 필터가 적용된 postCount와는 다른 "총 수집량" 지표라는 점에 유의(계약 문서 참조).
	 */
	public List<PostCountRow> countPostsByBrand(Collection<Long> brandIds) {
		if (brandIds.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbc.sql("""
				SELECT brand_id, count(*) AS post_count
				FROM brand_tagged_post
				WHERE brand_id IN (:brandIds)
				GROUP BY brand_id
				""")
				.param("brandIds", brandIds)
				.query(PostCountRow.class)
				.list();
	}

	/**
	 * 브랜드별 Hiker 콜 합(2026-09-03 어드민 브랜드 목록 계정 API) — {@link #findDailyCallCounts}(일별
	 * 원본, 유저별 연결 기간 귀속용)와 달리 여기서는 계정 단위 합계(전체·이번 달)만 필요해 SQL에서
	 * 미리 접는다. month는 호출부가 넘긴 {@code monthStart}(KST 이번 달 1일) 이후 콜만 필터
	 * (FILTER 절)로 골라 더한다 — sum()은 numeric을 돌려주므로 ::bigint 캐스트가 필수(record가 long).
	 */
	public List<BrandCallSumRow> sumCallCountsByBrand(Collection<Long> brandIds, LocalDate monthStart) {
		if (brandIds.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbc.sql("""
				SELECT brand_id,
				       SUM(calls)::bigint AS total,
				       COALESCE(SUM(calls) FILTER (WHERE called_on >= :monthStart), 0)::bigint AS month
				FROM brand_call_count
				WHERE brand_id IN (:brandIds)
				GROUP BY brand_id
				""")
				.param("brandIds", brandIds)
				.param("monthStart", monthStart)
				.query(BrandCallSumRow.class)
				.list();
	}

	/**
	 * 브랜드 풀(tagged ∪ direct) 게시물 조회(2026-08-18 direct 통합 §3-3, T10 — 옛
	 * {@code findTaggedPostsInWindow}/{@code findEnrichedTaggedPostsInWindow} 통합) — cutoff(365일
	 * 컷) 이후 taken_at인 행 <b>또는</b> direct 등록 행(창 예외, direct는 등록 시점이 아무리 오래돼도
	 * 표시된다)을 최신순으로 돌려준다. 개수 상한은 정책 v1(08-09)에서 폐지 — 모수는 수집 편입 컷
	 * (365일)이 이미 제한하고, 상한을 두면 12개월치가 많은 브랜드의 오래된 게시물이 소리 없이 잘린다.
	 *
	 * @param enrichedOnly true면 <b>보강 정산분만</b>(enriched_at IS NOT NULL) — 게시자 프로필·댓글이
	 *        붙기 전의 반쯤 빈 카드를 FE에 내보내지 않는다는 표시 표면 계약이다(2026-08-13 완결 배치
	 *        서빙 스펙 §5). 게시물 목록·상세와 그 {@code meta.counts}가 이 경로를 쓴다
	 *        ({@code BrandPostAssembler}의 {@code ENRICHED_ONLY}).
	 *        <p>false(전량)는 "있는데 없다고 답하면 안 되는" 판정 전용 — 캠페인 콘텐츠 존재 판정
	 *        (없으면 NOT_FOUND로 떨어진다), 직접 등록 중복 판정(놓치면 direct로 이중 등록된다), 성과
	 *        대시보드 지표 집계(미정산분도 스냅샷 지표는 이미 있어서 빼면 과소 계상)에 쓴다. 정산
	 *        여부는 <b>표시</b> 판정이지 존재 판정이 아니다(2026-08-13 완결 배치 서빙 리뷰 결정).
	 *
	 *        <p>정산은 "보강 <b>시도</b>가 끝났다"는 뜻이지 "필드가 다 찼다"가 아니다 — 게시자 조회가
	 *        404·타임아웃으로 소진되면 그 필드가 빈 채로 정산된다(실측 404 2%·타임아웃 1%). FE의 빈
	 *        필드 방어는 계속 필요하다.
	 */
	public List<BrandTaggedPostRow> findBrandPostsInWindow(long brandId, OffsetDateTime cutoff,
			boolean enrichedOnly) {
		String enrichedFilter = enrichedOnly ? " AND enriched_at IS NOT NULL" : "";
		return jdbc.sql("""
				SELECT short_code, author_username, author_ig_user_id, taken_at, first_seen_at,
				       comments_collected_count, last_crawled_at, tag_detected_at, direct_registered_at,
				       unavailable_at, hashtag_detected_at
				FROM brand_tagged_post
				WHERE brand_id = :brandId
				  AND ( taken_at >= :cutoff OR direct_registered_at IS NOT NULL )
				""" + enrichedFilter + """

				ORDER BY taken_at DESC
				""")
				.param("brandId", brandId)
				.param("cutoff", cutoff)
				.query(BrandTaggedPostRow.class)
				.list();
	}

	/** 태그 게시물 표시 메타(게시물 전역 최신 1행) — caption·thumbnailUrl·영상/유료협찬 필드의 산지. */
	public List<BrandPostMetaRow> findPostMeta(Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbc.sql("""
				SELECT short_code, username, content_type, uploaded_at, caption, thumbnail_url,
				       video_url, video_duration, is_paid_partnership, image_object_path,
				       ad_verdict, ad_violations::text AS ad_violations_json, ad_evidence::text AS ad_evidence_json
				FROM brand_post_meta
				WHERE short_code IN (:shortCodes)
				""")
				.param("shortCodes", shortCodes)
				.query(BrandPostMetaRow.class)
				.list();
	}

	/**
	 * 브랜드 게시물 인덱스 프로젝션(2026-08-27 목록 타임아웃 해소 설계) — counts·창·서버 필터·패싯·
	 * 정렬·페이지 슬라이스에 필요한 판정 입력만 <b>단일 쿼리</b>로 읽는다: 행 식별·창·source 파생
	 * (short_code·taken_at·tag_detected_at·direct_registered_at) + 협찬 판정 입력
	 * (is_paid_partnership·caption_marker) + 필터·패싯 입력(content_type·ad_verdict) + 작성자 판정
	 * 컬럼(author_profile 조인). 협찬 판정 자체는 was의 {@code BrandSponsorshipClassifier}가 조회 시
	 * 계산한다(저장 없음 — 키워드 개선이 과거분에 즉시 소급되는 설계라 버킷·컬럼으로 굳히지 않는다).
	 *
	 * <p><b>캡션 원문을 기본으로 싣지 않는 이유</b>(스테이징 실측 2026-08-27 perf119, marynmay 창 안
	 * 10,427행): 이 규모에선 DB 실행(EXPLAIN 21ms)이 아니라 <b>행×컬럼 값 전송·매핑</b>이 지배
	 * 비용이고, 그 고정비의 본체가 캡션 7.7MB 전송이다. 그래서 마커 매치를 SQL로 내려
	 * ({@code lower(caption) ~ :markerRegex}) boolean 1컬럼만 돌려받는다 — 캡션 자체는 페이지에 실릴
	 * 코드만 {@link #findPostMeta}가 다시 읽는다. 판정 트리는 자바와 동일하고({@code
	 * BrandSponsorshipClassifier.classify(Boolean, boolean)}), SQL↔Java 동치성은 골든 코퍼스 테스트가
	 * 봉인한다.
	 *
	 * <p><b>{@code withCaptions}</b>(2026-08-31 캡션 해시태그 탑재 설계) — 해시태그 추출이 필요한
	 * 브랜드 게시물 경로({@link BrandPostAssembler#indexForBrand})만 true로 호출해 캡션을 함께 실어
	 * 오고({@code m.caption}), 그 비용은 {@link BrandIndexCache} 캐시 미스(버전키 변경) 시에만
	 * 지불한다. 그 외 경로(성과 대시보드 {@code PerformanceContentAssembler.loadPoolIndex} — 캐시
	 * 없이 매 요청 조회하고 hashtags를 쓰지 않는다)는 false로 호출해 perf119 고정비를 계속 면제받는다
	 * — record 매핑은 컬럼명 기반이라 캡션 컬럼 자체는 항상 내려야 하므로 false일 땐 {@code NULL}을
	 * 같은 이름(caption)으로 내린다.
	 *
	 * <p>작성자 컬럼을 조인으로 함께 주는 이유: 목록의 인플루언서 필터·패싯이 인덱스 단계에서
	 * 결정돼야 페이지 슬라이스가 성립한다(하이드레이트는 이미 잘린 페이지만 본다). 조인 키가 null인
	 * 행(author_ig_user_id 미기입)은 author 컬럼이 전부 null로 오고 username 폴백 해소는 호출부 몫이다
	 * — 그래서 원시 관측 username(raw_author_username)도 함께 싣는다.
	 *
	 * <p>성과 대시보드 인덱스(2026-08-27 목록 최적화 설계)도 이 프로젝션을 상태(hidden)·작성자 판정에
	 * 함께 쓴다 — {@code unavailable_at}·{@code author_ig_user_id}는 short 값이라 폭 증가는 무시
	 * 수준이고(컬럼 추가는 이 기준으로만 허용), 협찬 판정은 브랜드 표면과 같은 caption_marker를 쓴다
	 * (캡션 전송 제거 이득을 대시보드도 함께 받는다).
	 *
	 * <p>창·정산 술어는 {@link #findBrandPostsInWindow}와 동형이어야 한다 — 어긋나면 counts가 목록
	 * 모수와 갈라진다. 메타 없는 행도 모수에 남도록 LEFT JOIN(판정 입력 null → unknown,
	 * caption_marker는 좌항 IS NOT NULL 가드 덕에 false). 정렬은 호출부(자바) 몫이라 ORDER BY를 두지
	 * 않는다.
	 *
	 * <p>매핑은 수동 람다다(2026-08-31) — 창 안 전 행(운영 1.6만 행대)을 싣는 쿼리라
	 * {@code query(Class)}의 이름 기반 리플렉션 매핑(행당 ~47µs 실측, raw 대비 20배)이 지배 비용이
	 * 된다. 조립 8.4초 분해는 2026-08-31 수동 RowMapper 설계 §1 참조.
	 *
	 * @param markerRegex {@code BrandSponsorshipClassifier.postgresMarkerRegex()} 산출물 — 소문자
	 *        캡션에 대한 ARE 정규식이다(호출부가 상수를 재작성하지 않게 그 메서드만 쓴다).
	 * @param withCaptions true면 {@code m.caption}을 실어 온다(해시태그 추출용, perf119 고정비
	 *        재지불), false면 같은 이름의 컬럼에 {@code NULL}을 내린다(record는 컬럼명 매핑이라 존재는
	 *        해야 한다).
	 */
	public List<BrandPostIndexRow> findBrandPostIndex(long brandId, OffsetDateTime cutoff,
			boolean enrichedOnly, String markerRegex, boolean withCaptions) {
		String enrichedFilter = enrichedOnly ? " AND t.enriched_at IS NOT NULL" : "";
		String captionColumn = withCaptions ? "m.caption," : "NULL AS caption,";
		return jdbc.sql("""
				SELECT t.short_code, t.taken_at, t.tag_detected_at, t.direct_registered_at,
				       t.hashtag_detected_at, t.unavailable_at, t.author_username AS raw_author_username,
				       t.author_ig_user_id,
				       m.is_paid_partnership,
				       (m.caption IS NOT NULL AND lower(m.caption) ~ :markerRegex) AS caption_marker,
				       """ + captionColumn + """

				       m.content_type, m.ad_verdict,
				       a.username AS author_username, a.full_name AS author_full_name,
				       a.profile_pic_url AS author_profile_pic_url,
				       a.image_object_path AS author_image_object_path,
				       a.followers AS author_followers
				FROM brand_tagged_post t
				LEFT JOIN brand_post_meta m ON m.short_code = t.short_code
				LEFT JOIN author_profile a ON a.ig_user_id = t.author_ig_user_id
				WHERE t.brand_id = :brandId
				  AND ( t.taken_at >= :cutoff OR t.direct_registered_at IS NOT NULL )
				""" + enrichedFilter)
				.param("brandId", brandId)
				.param("cutoff", cutoff)
				.param("markerRegex", markerRegex)
				.query((rs, i) -> new BrandPostIndexRow(
						rs.getString("short_code"),
						rs.getObject("taken_at", OffsetDateTime.class),
						rs.getObject("tag_detected_at", OffsetDateTime.class),
						rs.getObject("direct_registered_at", OffsetDateTime.class),
						rs.getObject("hashtag_detected_at", OffsetDateTime.class),
						rs.getObject("unavailable_at", OffsetDateTime.class),
						rs.getString("raw_author_username"),
						rs.getString("author_ig_user_id"),
						rs.getObject("is_paid_partnership", Boolean.class),
						rs.getBoolean("caption_marker"),
						rs.getString("content_type"),
						rs.getString("ad_verdict"),
						rs.getString("author_username"),
						rs.getString("author_full_name"),
						rs.getString("author_profile_pic_url"),
						rs.getString("author_image_object_path"),
						rs.getObject("author_followers", Long.class),
						rs.getString("caption")))
				.list();
	}

	/**
	 * 브랜드 풀 행 배치 조회(shortcode 스코프) — 하이드레이트가 페이지에 실을 코드만 풀 행으로
	 * 다시 읽는 용도({@link #findBrandPostIndex}가 경량 컬럼만 주므로). 페이지 상한(≤100)의 IN이라
	 * 인덱스 프로젝션과 달리 바인드 전개 부담이 없다.
	 */
	public List<BrandTaggedPostRow> findBrandPostsByShortCodes(long brandId, Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbc.sql("""
				SELECT short_code, author_username, author_ig_user_id, taken_at, first_seen_at,
				       comments_collected_count, last_crawled_at, tag_detected_at, direct_registered_at,
				       unavailable_at, hashtag_detected_at
				FROM brand_tagged_post
				WHERE brand_id = :brandId AND short_code IN (:shortCodes)
				""")
				.param("brandId", brandId)
				.param("shortCodes", shortCodes)
				.query(BrandTaggedPostRow.class)
				.list();
	}

	/**
	 * 게시물별 최신 스냅샷 1행의 지표 프로젝션(2026-08-27 대시보드 목록 최적화 설계에서 확장) —
	 * 정렬 키(views·likes·comments·engagement)·인플루언서 집계·대시보드 ref의 최신 지표 산출 전용.
	 * 시계열 전량({@link #findSnapshots})은 게시물당 최대 365행이라 지표만 필요한 경로에 싣지 않는다.
	 * content_type을 함께 주는 이유: 피드는 views를 null로 접는 서빙 규칙
	 * ({@code BrandPostAssembler.snapshotOf})을 호출부가 동일 적용해야 한다. likes_hidden은 "0"과
	 * "숨김"을 호출부가 구분하기 위한 것이다(숨김을 0으로 뭉개면 정렬·집계가 거짓말을 한다).
	 * 브랜드 창 스코프 조인인 이유는 {@link #findBrandPostIndex} 주석과 같다 — 창·정산 술어가
	 * 인덱스와 동형이어야 지표가 목록 모수와 어긋나지 않는다.
	 *
	 * <p>매핑은 수동 람다다(2026-08-31) — 게시물당 1행이라도 창 안 전 게시물(운영 1.5만 행대)을
	 * 싣는다. 근거는 {@link #findBrandPostIndex} 매핑 주석과 같다.
	 */
	public List<LatestSnapshotRow> findLatestSnapshotsForBrand(long brandId, OffsetDateTime cutoff,
			boolean enrichedOnly) {
		String enrichedFilter = enrichedOnly ? " AND t.enriched_at IS NOT NULL" : "";
		return jdbc.sql("""
				SELECT DISTINCT ON (s.short_code) s.short_code, s.captured_on, s.content_type,
				       s.views, s.likes, s.likes_hidden, s.comments
				FROM brand_post_snapshot s
				JOIN brand_tagged_post t ON t.short_code = s.short_code
				WHERE t.brand_id = :brandId
				  AND ( t.taken_at >= :cutoff OR t.direct_registered_at IS NOT NULL )
				""" + enrichedFilter + """

				ORDER BY s.short_code, s.captured_on DESC
				""")
				.param("brandId", brandId)
				.param("cutoff", cutoff)
				.query((rs, i) -> new LatestSnapshotRow(
						rs.getString("short_code"),
						rs.getObject("captured_on", LocalDate.class),
						rs.getString("content_type"),
						rs.getObject("views", Long.class),
						rs.getObject("likes", Long.class),
						rs.getBoolean("likes_hidden"),
						rs.getObject("comments", Long.class)))
				.list();
	}

	/**
	 * 게시물별 최신 스냅샷 좋아요·댓글·조회수(shortcode 스코프, 2026-08-28 AI aggregate_posts 전용) —
	 * {@link #findLatestSnapshotsForBrand}처럼 브랜드 전체를 창·정산 조건으로 다시 훑지 않고, 호출부(AI
	 * 툴박스)가 인덱스에서 이미 걸러낸 shortcode 집합만 받는다({@link #findBrandPostsByShortCodes}와
	 * 같은 위상 — 인덱스 프로젝션의 후속 배치 조회). content_type을 함께 주는 이유는
	 * findLatestSnapshotsForBrand와 동일(피드 views null 서빙 규칙을 호출부가 재현해야 한다).
	 */
	public List<LatestMetricsRow> findLatestMetricsByShortCodes(Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbc.sql("""
				SELECT DISTINCT ON (short_code) short_code, content_type, views, likes, comments
				FROM brand_post_snapshot
				WHERE short_code IN (:shortCodes)
				ORDER BY short_code, captured_on DESC
				""")
				.param("shortCodes", shortCodes)
				.query(LatestMetricsRow.class)
				.list();
	}

	/**
	 * 캡션 부분일치 검색(2026-08-28 AI search_posts 전용) — 한글 제품명이 공백 유무로 갈리는 걸
	 * 흡수하기 위해 캡션·질의 양쪽에서 공백을 제거한 뒤 ILIKE로 비교한다. 호출부가 인덱스에서 이미
	 * 걸러낸(가시성·창) shortcode 집합만 넘기므로 여기선 그 범위 안에서 캡션 열만 읽는다 — 창 안
	 * 전체를 하이드레이트하지 않고 "정확한 총 매칭 건수"를 얻기 위한 가벼운 경로다(하이드레이트는
	 * 상위 노출분에만 쓴다). query는 파라미터 바인딩이라 {@code %}·{@code '} 등이 섞여도 SQL 인젝션과
	 * 무관하다.
	 */
	public Set<String> findCaptionMatches(Collection<String> shortCodes, String normalizedQuery) {
		if (shortCodes.isEmpty() || normalizedQuery.isEmpty()) {
			return Set.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return new LinkedHashSet<>(jdbc.sql("""
				SELECT short_code
				FROM brand_post_meta
				WHERE short_code IN (:shortCodes)
				  AND caption IS NOT NULL
				  AND replace(caption, ' ', '') ILIKE ('%' || :normalizedQuery || '%')
				""")
				.param("shortCodes", shortCodes)
				.param("normalizedQuery", normalizedQuery)
				.query(String.class)
				.list());
	}

	/**
	 * 게시물별 일 단위 지표 시계열 전량(오름차순) — 브랜드 스냅샷은 윈도우 이탈 후에도 영구
	 * 보존이라 상한(워터마크)을 두지 않는다. views는 DDL 주석대로 이미 화면 합산값(IG 몫 + FB 몫)이라
	 * fb_plays를 따로 읽지 않는다.
	 */
	public List<BrandSnapshotRow> findSnapshots(Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT short_code, captured_on, content_type, likes, likes_hidden, comments, views,
				       saves, shares, shares_hidden, reposts
				FROM brand_post_snapshot
				WHERE short_code IN (:shortCodes)
				ORDER BY short_code, captured_on
				""")
				.param("shortCodes", shortCodes)
				.query(BrandSnapshotRow.class)
				.list();
	}

	/**
	 * 게시물별 최신 댓글 상한 {@code perPostLimit}건(레거시 findComments와 같은 윈도우 관용구).
	 *
	 * <p>상한을 SQL 단계에서 자르는 이유: 브랜드 댓글은 매 스윕이 최대 45건씩 누적 합집합으로 쌓고
	 * 행 삭제가 없어(08-06 스키마) 인기 게시물은 365일 윈도우 동안 수천 행까지 간다 — 상한 없이
	 * 가져오면 "윈도우 전 게시물 × 전량"이 한 번에 힙에 올라온다. 표시 상한을 호출부가 자르는
	 * 방식으로는 그 비용을 못 줄인다.
	 */
	public List<BrandCommentRow> findComments(Collection<String> shortCodes, int perPostLimit) {
		if (shortCodes.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT short_code, id, author, body, like_count, commented_at, owner_reply_text
				FROM (
				    SELECT short_code, id, author, body, like_count, commented_at, owner_reply_text,
				           row_number() OVER (PARTITION BY short_code ORDER BY commented_at DESC) AS rn
				    FROM brand_post_comment
				    WHERE short_code IN (:shortCodes)
				) ranked
				WHERE rn <= :perPostLimit
				ORDER BY short_code, commented_at DESC
				""")
				.param("shortCodes", shortCodes)
				.param("perPostLimit", perPostLimit)
				.query(BrandCommentRow.class)
				.list();
	}

	/**
	 * 게시자(인플루언서) 프로필 — 기본 경로. author_profile의 PK가 ig_user_id라 중복이 없다.
	 *
	 * <p>게시자 프로필 배치 조회 — 매핑은 수동 람다(2026-08-31, 근거는 {@link #findBrandPostIndex} 참조).
	 */
	public List<AuthorRow> findAuthors(Collection<String> igUserIds) {
		if (igUserIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT ig_user_id, username, full_name, followers, profile_pic_url, is_verified,
				       image_object_path
				FROM author_profile
				WHERE ig_user_id IN (:igUserIds)
				""")
				.param("igUserIds", igUserIds)
				.query((rs, i) -> new AuthorRow(
						rs.getString("ig_user_id"),
						rs.getString("username"),
						rs.getString("full_name"),
						rs.getObject("followers", Long.class),
						rs.getString("profile_pic_url"),
						rs.getObject("is_verified", Boolean.class),
						rs.getString("image_object_path")))
				.list();
	}

	/**
	 * 게시자 프로필 폴백 경로 — brand_tagged_post.author_ig_user_id가 null(열거 셰이프에 따라 발생)일
	 * 때 username으로 찾는다. username은 author_profile의 유니크 키가 아니라(PK는 ig_user_id) 계정
	 * 이름 변경 이력 등으로 같은 이름이 여러 행일 수 있어, DISTINCT ON으로 계정당 최신
	 * (fetched_at DESC) 1행만 돌려준다 — 호출부의 username→프로필 맵 구성이 중복으로 깨지지 않게.
	 *
	 * <p>ig_user_id DESC 타이브레이크가 붙는 이유: fetched_at은 같은 스윕 트랜잭션에서 적재된 행들이
	 * 동일값이 될 수 있어(Postgres now()는 트랜잭션 내 불변) 동점 시 어느 행이 남는지가 비결정적이다.
	 */
	public List<AuthorRow> findAuthorsByUsername(Collection<String> usernames) {
		if (usernames.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT DISTINCT ON (username)
				       ig_user_id, username, full_name, followers, profile_pic_url, is_verified,
				       image_object_path
				FROM author_profile
				WHERE username IN (:usernames)
				ORDER BY username, fetched_at DESC, ig_user_id DESC
				""")
				.param("usernames", usernames)
				.query(AuthorRow.class)
				.list();
	}

	/**
	 * 브랜드 해시태그 발견 게시물(스펙 2026-08-11 §5) — RELEVANT(관련 판정)만, cutoff 이후 taken_at
	 * 최신순 상한 limit건. tagged와 달리 상한을 SQL 단계에서 자른다 — 해시태그 발견분은 태그 게시물과
	 * 달리 등록 시점 검증이 없어 폭주 가능성이 tagged보다 크다(스펙 §5, 서빙 상한은 호출부 정책).
	 */
	public List<BrandHashtagPostRow> findHashtagPosts(long brandId, OffsetDateTime cutoff, int limit) {
		return jdbc.sql("""
				SELECT short_code, matched_tag, author_username, author_full_name,
				       author_profile_pic_url, taken_at, caption, content_type, thumbnail_url,
				       likes, comments, first_seen_at, image_object_path, author_image_object_path
				FROM brand_hashtag_post
				WHERE brand_id = :brandId AND verdict = 'RELEVANT' AND taken_at >= :cutoff
				ORDER BY taken_at DESC
				LIMIT :limit
				""")
				.param("brandId", brandId).param("cutoff", cutoff).param("limit", limit)
				.query(BrandHashtagPostRow.class).list();
	}

	/**
	 * 지난주 <b>태그 열거로 새로 발견된</b> 게시물 + 최신 스냅샷 지표(설계 §4 브랜드 새 게시물).
	 * direct 등록분은 제외한다 — 사용자가 스스로 넣은 게시물은 "발견 소식"이 아니다.
	 * 스냅샷이 아직 없는 발견분도 모수에 남도록 LEFT JOIN이며, 그때 지표는 전부 null이다.
	 *
	 * <p>2026-08-28 품질 리뷰 I3: 잡이 유저마다 호출하던 것을 전 유저 브랜드id를 한데 모아 <b>1회</b>
	 * 호출하는 방식으로 바뀌었다 — brandIds가 이제 여러 유저의 브랜드를 함께 담을 수 있어, DISTINCT ON을
	 * short_code 단독이 아니라 (brand_id, short_code)로 건다. short_code 단독으로 접으면 브랜드 A·B가
	 * 각각 발견한 동일 shortCode 중 하나가 사라져 그 브랜드를 보는 유저가 소식을 못 받는다 — 유저별
	 * 최종 dedup(같은 유저가 여러 브랜드를 걸고 있어 겹치는 경우)은 호출부({@code WeeklyDigestJob})가
	 * brandId로 되짚은 뒤 shortCode 기준으로 한다.
	 */
	public List<WeeklyPostMetrics> findTaggedPostsDiscoveredBetween(Collection<Long> brandIds,
			OffsetDateTime from, OffsetDateTime toExclusive) {
		if (brandIds.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbc.sql("""
				SELECT DISTINCT ON (t.brand_id, t.short_code) t.brand_id, t.short_code, t.author_username,
				       s.content_type, s.views, s.likes, s.comments
				FROM brand_tagged_post t
				LEFT JOIN brand_post_snapshot s ON s.short_code = t.short_code
				WHERE t.brand_id IN (:brandIds)
				  AND t.direct_registered_at IS NULL
				  AND t.tag_detected_at >= :from AND t.tag_detected_at < :toExclusive
				ORDER BY t.brand_id, t.short_code, s.captured_on DESC NULLS LAST
				""")
				.param("brandIds", brandIds)
				.param("from", from)
				.param("toExclusive", toExclusive)
				.query(WeeklyPostMetrics.class)
				.list();
	}

	/**
	 * 지난주 <b>해시태그 스윕이 새로 발견한</b> 관련 게시물(설계 §4). 이 표면은 스냅샷·보강이
	 * 없어(스펙 2026-08-11 §5 보류) 지표가 열거 관측값 그대로고 조회수 자체가 없다 — views는
	 * 항상 null로 내려 합산 규칙(릴스만 조회수)과 자연히 정합한다.
	 *
	 * <p>2026-08-28 품질 리뷰 I3: {@link #findTaggedPostsDiscoveredBetween}와 같은 이유로
	 * DISTINCT ON을 (brand_id, short_code)로 건다 — brandIds가 이제 전 유저 배치 호출의 합집합이다.
	 */
	public List<WeeklyPostMetrics> findHashtagPostsDiscoveredBetween(Collection<Long> brandIds,
			OffsetDateTime from, OffsetDateTime toExclusive) {
		if (brandIds.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbc.sql("""
				SELECT DISTINCT ON (brand_id, short_code) brand_id, short_code, author_username, content_type,
				       NULL::bigint AS views, likes, comments
				FROM brand_hashtag_post
				WHERE brand_id IN (:brandIds) AND verdict = 'RELEVANT'
				  AND first_seen_at >= :from AND first_seen_at < :toExclusive
				ORDER BY brand_id, short_code, first_seen_at DESC
				""")
				.param("brandIds", brandIds)
				.param("from", from)
				.param("toExclusive", toExclusive)
				.query(WeeklyPostMetrics.class)
				.list();
	}

	/**
	 * 지난주 <b>광고 미표기</b>로 판정된 shortcode 전체(설계 §4 광고 미표기, 2026-08-28 재리뷰
	 * nit로 술어 역전) — brand_post_meta 전체에서 창 하나로만 걸러 읽는다. 등록 원장(app
	 * 스키마의 그 유저가 등록한 게시물)과의 교집합은 <b>호출부(was 코드)가 자바에서</b> 계산한다 —
	 * monitoring DB와 app 스키마를 SQL로 조인하지 않는다는 시스템 경계는 그대로다.
	 *
	 * <p>이전에는 반대로 shortcode 후보 목록을 {@code IN (:shortCodes)}로 통째로 바인드했는데,
	 * 유저 수만큼 왕복하지 않는 배치 조회(품질 리뷰 I3)로 바뀌면서 그 목록이 "전 유저의 등록
	 * shortcode 합집합"이 돼 바인드 파라미터 상한에 걸릴 수 있었다. ad_verdict·창 조건만으로
	 * 걸러 읽는 편이 원본 SQL 파라미터 없이 인덱스를 태우기도 더 쉽다. 판정 컬럼의 실제 위치는
	 * brand_post_meta다(V20260817160000).
	 */
	public List<String> findNotDisclosedJudgedBetween(OffsetDateTime from, OffsetDateTime toExclusive) {
		return jdbc.sql("""
				SELECT short_code FROM brand_post_meta
				WHERE ad_verdict = 'NOT_DISCLOSED' AND ad_judged_at >= :from AND ad_judged_at < :toExclusive
				ORDER BY short_code
				""")
				.param("from", from)
				.param("toExclusive", toExclusive)
				.query(String.class)
				.list();
	}

	/**
	 * 해시태그 발견 게시물의 shortcode만(2026-08-27 서버 필터·패싯 설계) — counts와 tagged 풀과의
	 * 교차 중복 제거처럼 <b>코드만</b>이면 되는 경로용. {@link #findHashtagPosts}와 WHERE·ORDER·LIMIT가
	 * 동형이어야 한다(어긋나면 counts가 목록 모수와 갈라진다) — 캡션·썸네일 등 표시 컬럼 전송만 뺀 것이
	 * 유일한 차이다.
	 */
	public List<String> findHashtagPostCodes(long brandId, OffsetDateTime cutoff, int limit) {
		return jdbc.sql("""
				SELECT short_code
				FROM brand_hashtag_post
				WHERE brand_id = :brandId AND verdict = 'RELEVANT' AND taken_at >= :cutoff
				ORDER BY taken_at DESC
				LIMIT :limit
				""")
				.param("brandId", brandId).param("cutoff", cutoff).param("limit", limit)
				.query(String.class).list();
	}

	/**
	 * 통합 풀 게시물의 매칭 태그 전체(2026-08-19 신설 → <b>2026-08-27 산지 교체</b>) —
	 * {@code brand_post_matched_tag}(스윕이 게시물당 매칭된 활성 태그 전부를 기록하는 M:N 테이블).
	 * 구 산지 {@code brand_hashtag_post_matched_tags}는 감지 구조 폐기와 함께 읽기를 중단했다
	 * (테이블 DROP은 다음 릴리스 — expand-contract).
	 *
	 * <p>조회자 본인의 태그 원장({@code app.brand_hashtag_tags})과의 교집합 판정은 was 코드
	 * ({@code BrandPostAssembler.filterVisibleToUser}·{@code BrandHashtagPostAssembler})가 한다 —
	 * 여기서는 monitoring DB만 읽는다(시스템 경계, app 스키마와 SQL 조인 금지).
	 */
	public List<MatchedTagRow> findMatchedTags(long brandId, Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbc.sql("""
				SELECT short_code, tag FROM brand_post_matched_tag
				WHERE brand_id = :brandId AND short_code IN (:shortCodes)
				""")
				.param("brandId", brandId).param("shortCodes", shortCodes)
				.query(MatchedTagRow.class).list();
	}

	/**
	 * 브랜드 풀 상태(2026-08-18 direct 통합 §2-3·§2-5·§T9) — 중복 판정(direct 등록)·발견 목록 판정
	 * (hashtag-posts)·취소 판정 공용 배치 조회. {@code tag_detected_at}·{@code direct_registered_at}
	 * 원시값 대신 boolean으로 파생해 호출부의 null 분기를 없앤다. 윈도우(365일 컷) 제한이 없는 순수
	 * 존재 판정이다 — {@link #findExistingTaggedShortCodes}와 같은 위상.
	 */
	public List<BrandPoolStatusRow> findBrandPoolStatus(long brandId, Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbc.sql("""
				SELECT short_code,
				       tag_detected_at IS NOT NULL      AS tag_detected,
				       direct_registered_at IS NOT NULL AS direct_registered,
				       taken_at
				  FROM brand_tagged_post
				 WHERE brand_id = :brandId AND short_code IN (:shortCodes)
				""")
				.param("brandId", brandId).param("shortCodes", shortCodes)
				.query(BrandPoolStatusRow.class)
				.list();
	}

	/**
	 * 후보 shortcode 중 그 브랜드의 tagged 게시물로 실재하는 것들(2026-08-17 승격 상태 필드 §스펙) —
	 * 윈도우(365일 컷) 제한이 없는 순수 존재 판정이다. 표시용 전량 조립({@link #findTaggedPostsInWindow}·
	 * {@link #findPostMeta} 등)을 태우지 않는 이유: 해시태그 발견 목록 조립에 매 요청 무거운 태그
	 * 게시물 전량 조립을 끼워 넣지 않기 위해서다(성능 — 존재 판정만 필요, 표시 필드는 불필요).
	 */
	public Set<String> findExistingTaggedShortCodes(long brandId, Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return Set.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return new LinkedHashSet<>(jdbc.sql("""
				SELECT short_code FROM brand_tagged_post WHERE brand_id = :brandId AND short_code IN (:shortCodes)
				""")
				.param("brandId", brandId).param("shortCodes", shortCodes)
				.query(String.class).list());
	}

	/**
	 * 브랜드별 Hiker 콜 일별 집계(brand_call_count — 2026-08-12 어드민 크롤링 비용 설계) 전량 조회.
	 * 기간 필터·유저 귀속(연결 기간 판정)은 was 코드가 한다 — 링크 기간은 app 스키마 소관이라
	 * 크로스 DB 조인이 불가능하고, 행 수도 브랜드당 하루 1행이라 전량이 부담이 아니다.
	 */
	public List<BrandCallDailyRow> findDailyCallCounts(Collection<Long> brandIds) {
		if (brandIds.isEmpty()) {
			return List.of();   // IN () 은 SQL 오류 — 빈 입력 선처리
		}
		return jdbc.sql("""
				SELECT brand_id, called_on, calls
				FROM brand_call_count
				WHERE brand_id IN (:brandIds)
				""")
				.param("brandIds", brandIds)
				.query(BrandCallDailyRow.class)
				.list();
	}

	/**
	 * 전 브랜드 날짜별 콜 합(설계 2026-08-13 §3-4) — 어드민 전역 크롤링 비용 API의 브랜드 몫.
	 * 유저별 카드({@link #findDailyCallCounts})와 달리 연결 기간으로 자르지 않는다: 공유 브랜드는
	 * 유저마다 계상되므로 유저별 값을 더하면 실제로 나간 돈보다 커진다. 전사 합계는 브랜드 축에서
	 * 직접 합산해야 정확하다.
	 *
	 * <p>sum()은 numeric을 돌려주므로 ::bigint 캐스트가 필수다(record 컴포넌트가 long).
	 */
	public List<DailyCallSum> sumDailyCallCounts() {
		return jdbc.sql("""
				SELECT called_on, sum(calls)::bigint AS calls
				FROM brand_call_count
				GROUP BY called_on
				""")
				.query(DailyCallSum.class)
				.list();
	}

	/**
	 * brand_account 1행(was 계약 소비 컬럼만) — 08-07 확장 필드 포함. imageObjectPath는 monitoring
	 * 자체 프로필 이미지 아카이브 결과(V20260811023454) — null이면 아직 아카이브 전이라 서빙 측이
	 * 원본 CDN URL로 폴백한다.
	 *
	 * <p>collectionMonths는 자산 레벨 수집 창(공유 유저 간 max — 스펙 2026-08-12), collectionStartedAt은
	 * 확장 시 갱신되는 폴링 앵커(기존 행은 registered_at 폴백).
	 *
	 * <p>collectionCapped·coveredUntil은 백필 시점의 창 커버리지(수집 상한 v2 §7-1,
	 * V20260819125244) — capped=true면 백필이 수집 개수 상한(2,000)에서 끊겼고 coveredUntil이 그
	 * 실수집 깊이(이 시각 이후 구간만 수집됨)다. coveredUntil null = 요청 창 전체 커버.
	 * 일일 스윕은 이 값을 갱신하지 않는다(창 커버리지는 백필 속성).
	 */
	public record BrandAccountRow(long id, String username, LocalDate lastSweptOn, OffsetDateTime lastSweptAt,
			OffsetDateTime registeredAt, OffsetDateTime backfillCompletedAt, String backfillError,
			Long followers, Long following, Long mediaCount, String biography, String fullName,
			String profilePicUrl, Boolean isVerified, String externalUrl, String status,
			String imageObjectPath, int collectionMonths, OffsetDateTime collectionStartedAt,
			boolean collectionCapped, OffsetDateTime coveredUntil) {
	}

	/**
	 * brand_tagged_post 1행 — 게시물 지표·메타는 여기 없다(게시물 전역 테이블에서 배치 조회).
	 *
	 * <p>2026-08-18 direct 통합(T10)으로 3필드 추가: {@code lastCrawledAt}(브랜드 스윕·direct 단건
	 * 수집이 이 행을 마지막으로 건드린 시각 — {@code updatedAt} 산정에 쓴다), {@code tagDetectedAt}
	 * (태그 열거가 이 링크를 처음 만난 시각, null이면 direct-only), {@code directRegisteredAt}
	 * (직접 등록 시각, null이면 tagged-only — 둘 다 있으면 겹침 행). {@code source} 파생과
	 * {@code trackingStartedAt} 산정의 원천이다({@code BrandPostAssembler.brandPost}).
	 *
	 * <p>{@code unavailableAt}(야간 스윕 단건 콜이 404를 받은 시각, null이면 정상 — 값이 있으면
	 * trackingStatus가 hidden으로 내려간다, 2026-08-25 설계).
	 *
	 * <p>{@code hashtagDetectedAt}(2026-08-27 해시태그 직접 수집 설계 §1) — 해시태그 열거가 이
	 * 게시물을 처음 편입한 시각. 세 타임스탬프가 모두 null이 아닐 수 있고(3성분 겹침), tag·direct가
	 * 둘 다 null이면 hashtag-only 행이다. {@code source} 3원화와 사용자 격리 필터의 입력이다
	 * ({@code BrandPostAssembler.resolveSource}·{@code filterVisibleToUser}).
	 */
	public record BrandTaggedPostRow(String shortCode, String authorUsername, String authorIgUserId,
			OffsetDateTime takenAt, OffsetDateTime firstSeenAt, long commentsCollectedCount,
			OffsetDateTime lastCrawledAt, OffsetDateTime tagDetectedAt, OffsetDateTime directRegisteredAt,
			OffsetDateTime unavailableAt, OffsetDateTime hashtagDetectedAt) {
	}

	/**
	 * brand_post_meta 1행. isPaidPartnership null = 응답 키 부재(판정 unknown 근거).
	 * imageObjectPath는 monitoring 자체 썸네일 아카이브 결과 — null이면 원본 CDN URL 폴백.
	 * adVerdict null = 미판정(광고 표기 판정 스펙 §4). adViolationsJson·adEvidenceJson은
	 * jsonb를 텍스트로 읽은 원문 — 파싱은 {@link BrandPostAssembler}가 한다(null 가능).
	 */
	public record BrandPostMetaRow(String shortCode, String username, String contentType, LocalDate uploadedAt,
			String caption, String thumbnailUrl, String videoUrl, Double videoDuration,
			Boolean isPaidPartnership, String imageObjectPath, String adVerdict, String adViolationsJson,
			String adEvidenceJson) {
	}

	/**
	 * 브랜드 게시물 인덱스 1행({@link #findBrandPostIndex}) — isPaidPartnership null은 "메타
	 * 미보강(LEFT JOIN 미스)"과 "키 부재" 둘 다일 수 있고 어느 쪽이든 판정은 unknown이라 구분하지
	 * 않는다. tagDetectedAt·directRegisteredAt·hashtagDetectedAt은 source 3원화·노출 필터 입력이다
	 * (2026-08-27 해시태그 직접 수집 설계 §3 — {@code BrandTaggedPostRow}의 같은 세 필드와 동형,
	 * {@code BrandPostAssembler.indexForBrand}가 소비한다).
	 *
	 * <p>{@code captionMarker}는 캡션 원문 대신 SQL이 계산한 협찬 마커 매치 결과다(항상 non-null —
	 * 메타 행이 없으면 false). {@code rawAuthorUsername}은 brand_tagged_post의 원시 관측값이고,
	 * {@code author*} 5필드는 author_profile 조인 결과라 조인 미스면 전부 null이다 — username 폴백
	 * 해소는 호출부(어셈블러) 몫이라는 뜻이다.
	 *
	 * <p>{@code unavailableAt}·{@code authorIgUserId}는 성과 대시보드 인덱스(2026-08-27 목록 최적화
	 * 설계)의 상태(hidden)·작성자 판정 입력이다 — 브랜드 표면은 쓰지 않는다.
	 *
	 * <p>{@code caption}(2026-08-31 캡션 해시태그 탑재)은 {@code findBrandPostIndex}의
	 * {@code withCaptions} 인자가 true일 때만 값이 있다 — false로 호출한 경로(성과 대시보드)는 항상
	 * null이고, 이건 "메타 미보강"과 구분되지 않는 값이니 caption null을 "캡션이 원래 없다"는 판정에
	 * 쓰면 안 된다(그 판정은 captionMarker가 이미 SQL에서 끝냈다).
	 *
	 * <p>contentType·authorUsername·authorFollowers는 AI 어시스턴트 scope 필터(mediaType·작성자
	 * 검색·팔로워 필터, 2026-08-30 FE 계약 개편)의 입력이기도 하다 —
	 * {@code com.celfit.was.v1.brandmonitoring.ai.BrandAiToolbox}가 {@code indexForBrand} 경유로 소비한다.
	 */
	public record BrandPostIndexRow(String shortCode, OffsetDateTime takenAt, OffsetDateTime tagDetectedAt,
			OffsetDateTime directRegisteredAt, OffsetDateTime hashtagDetectedAt, OffsetDateTime unavailableAt,
			String rawAuthorUsername, String authorIgUserId, Boolean isPaidPartnership, boolean captionMarker,
			String contentType, String adVerdict, String authorUsername, String authorFullName,
			String authorProfilePicUrl, String authorImageObjectPath, Long authorFollowers, String caption) {
	}

	/**
	 * 게시물별 최신 스냅샷 지표 1행({@link #findLatestSnapshotsForBrand}) — contentType은 피드 views
	 * null 규칙용, likesHidden은 "0"과 "숨김"을 구분하는 플래그다(숨김이면 likes는 null).
	 */
	public record LatestSnapshotRow(String shortCode, LocalDate capturedOn, String contentType,
			Long views, Long likes, boolean likesHidden, Long comments) {
	}

	/** 게시물별 최신 스냅샷 좋아요·댓글·조회수({@link #findLatestMetricsByShortCodes}) — aggregate_posts 전용. */
	public record LatestMetricsRow(String shortCode, String contentType, Long views, Long likes, Long comments) {
	}

	/** brand_post_snapshot 1행 — 컬럼 구성은 레거시 post_snapshot과 동형(캐리포워드 규칙 이식 전제). */
	public record BrandSnapshotRow(String shortCode, LocalDate capturedOn, String contentType, Long likes,
			boolean likesHidden, Long comments, Long views, Long saves, Long shares, boolean sharesHidden,
			Long reposts) {
	}

	/** brand_post_comment 1행. */
	public record BrandCommentRow(String shortCode, String id, String author, String body, long likeCount,
			OffsetDateTime commentedAt, String ownerReplyText) {
	}

	/**
	 * author_profile 1행(was 계약 소비 컬럼만). imageObjectPath는 monitoring 자체 프로필 이미지
	 * 아카이브 결과(V20260807150500) — null이면 원본 CDN URL 폴백.
	 */
	public record AuthorRow(String igUserId, String username, String fullName, Long followers,
			String profilePicUrl, Boolean isVerified, String imageObjectPath) {
	}

	/**
	 * brand_hashtag_post 1행(RELEVANT만) — 프로필 보강·스냅샷이 없어(스펙 §5 보류) author 필드는
	 * 열거 관측값 그대로고 followers·isVerified는 아예 없다. likes·comments도 열거 시점 관측값이다.
	 * imageObjectPath는 monitoring 자체 썸네일 아카이브 결과 — null이면 원본 CDN URL 폴백.
	 * authorImageObjectPath는 작성자 프로필 사진 아카이브 결과(2026-08-17 신설,
	 * V20260817142317__hashtag_post_author_image_archive.sql) — null이면 원본 CDN URL 폴백.
	 */
	public record BrandHashtagPostRow(String shortCode, String matchedTag, String authorUsername,
			String authorFullName, String authorProfilePicUrl, OffsetDateTime takenAt, String caption,
			String contentType, String thumbnailUrl, Long likes, Long comments,
			OffsetDateTime firstSeenAt, String imageObjectPath, String authorImageObjectPath) {
	}

	/** brand_post_matched_tag 1행({@link #findMatchedTags}) — shortcode 1건에 매칭 태그 1건. */
	public record MatchedTagRow(String shortCode, String tag) {
	}

	/** brand_call_count 1행 — calledOn은 KST 달력일(집계 경계 계산도 KST — 쓰는 쪽과 정합). */
	public record BrandCallDailyRow(long brandId, LocalDate calledOn, long calls) {
	}

	/** 브랜드별 총 게시물 수({@link #countPostsByBrand}) — 어드민 목록 postCount("총 수집량")의 원천. */
	public record PostCountRow(long brandId, long postCount) {
	}

	/** 브랜드별 콜 합계({@link #sumCallCountsByBrand}) — total은 전체 기간, month는 KST 이번 달분. */
	public record BrandCallSumRow(long brandId, long total, long month) {
	}

	/**
	 * 브랜드 풀 상태 1행({@link #findBrandPoolStatus}) — taken_at은 direct-only 신규 행에도 항상 값이
	 * 있다(brand_tagged_post.taken_at NOT NULL, direct 등록도 단건 콜의 게시일로 채운다).
	 */
	public record BrandPoolStatusRow(String shortCode, boolean tagDetected, boolean directRegistered,
			OffsetDateTime takenAt) {
	}

	// 시딩(협업) username 조회(findSeededUsernames, brand_seeded_account 유래)는 2026-08-18 캠페인 도출
	// 개정으로 제거됐다 — seededAuthor는 이제 was BrandPostAssembler.resolveSeededUsernames가 기존
	// 캠페인 관리 데이터(MonitoringItemRepository·BrandDirectPostRepository·BrandPostCampaignRepository)
	// 에서 산출한다. brand_seeded_account 테이블·마이그레이션 자체는 expand-contract상 존치.
}

package com.celfit.was.monitoring;

import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 어드민 "등록된 브랜드 목록"(GET /v1/admin/brand-monitoring/accounts) 전용 조회(2026-09-04) — 이
 * 세 메서드는 {@code AdminBrandAccountService}만 쓴다. 원래 {@link BrandReadRepository}에 있던 걸
 * 분리했다 — 게시물 수·콜 합계 집계(GROUP BY)가 무거운데, 같은 풀(monitoring-ro)을 실사용자 브랜드
 * 대시보드·AI 어시스턴트와 공유해서 어드민 화면이 정렬·페이지만 바꿔도 그 풀(max=3)을 붙들어
 * 사용자 API를 밀어냈다(staging 실측: 어드민 20연속 호출 중 사용자 API p95 8.9배). 이 리포지토리는
 * 별도 커넥션 풀(monitoring-admin, {@link MonitoringConfig})로 조회해 경합을 원천 차단한다.
 *
 * <p>주입되는 JdbcClient는 {@link MonitoringConfig}가 admin 전용으로 만든 별도 커넥션이다
 * ({@link BrandReadRepository}가 쓰는 monitoring-ro와 다른 풀). app 스키마·분석 결과와의 크로스 DB
 * 조인 금지 — 나머지 제약은 {@link BrandReadRepository} 클래스 주석과 동일.
 */
public class AdminBrandReadRepository {

	private final JdbcClient jdbc;

	public AdminBrandReadRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 브랜드 계정 배치 조회(2026-09-03 어드민 브랜드 목록 계정 API) — {@link BrandReadRepository#findAccount}와
	 * 같은 컬럼 집합을 여러 브랜드에 대해 한 왕복으로 읽는다. PK IN이라 가벼워 호출부가 페이지가
	 * 아니라 항상 전체 brandId 집합으로 부른다.
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
	 *
	 * <p>전량 GROUP BY라 무겁다(2026-09-04) — 호출부(어드민 목록 서비스)는 정렬 키가 postCount·
	 * crawlingCalls일 때만 전체 brandId로 부르고, 그 외 정렬에서는 페이지에 남은 ≤limit개
	 * brandId로만 좁혀 부른다.
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
	 * 브랜드별 Hiker 콜 합(2026-09-03 어드민 브랜드 목록 계정 API) — 계정 단위 합계(전체·이번 달)만
	 * 필요해 SQL에서 미리 접는다. month는 호출부가 넘긴 {@code monthStart}(KST 이번 달 1일) 이후
	 * 콜만 필터(FILTER 절)로 골라 더한다 — sum()은 numeric을 돌려주므로 ::bigint 캐스트가 필수
	 * (record가 long).
	 *
	 * <p>{@link #countPostsByBrand}와 같은 이유로 전량 GROUP BY가 무겁다 — 호출 범위 규칙도 같다.
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

	/** 브랜드별 총 게시물 수({@link #countPostsByBrand}) — 어드민 목록 postCount("총 수집량")의 원천. */
	public record PostCountRow(long brandId, long postCount) {
	}

	/** 브랜드별 콜 합계({@link #sumCallCountsByBrand}) — total은 전체 기간, month는 KST 이번 달분. */
	public record BrandCallSumRow(long brandId, long total, long month) {
	}
}

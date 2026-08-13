package com.celfit.was.crawlcost;

import com.celfit.contract.analysis.CrawlCallDaily;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 크롤러 파이프라인 유료 요청 일별 집계 조회(설계 2026-08-13) — analytics 미러가 채우는
 * analysis DB의 crawl_call_daily. was는 이 테이블로만 크롤러 비용을 본다(raw DB 접근 금지).
 *
 * <p>기본 데이터소스(analysis DB)라 스키마 접두어가 없다 — contents·account_summaries와 같은 자리.
 *
 * <p>전량 조회인 이유: 행 수가 (잡 × 날짜)로 접혀 있어 파이프라인 5종 × 운영 일수 규모다.
 * 세 구간(전체·이번 달·오늘) 중 "전체"가 결국 전 기간을 요구하므로 기간 필터가 무의미하다.
 */
@Repository
public class CrawlCallDailyRepository {

	private final JdbcClient jdbcClient;

	public CrawlCallDailyRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public List<CrawlCallDaily> findAll() {
		return jdbcClient.sql("SELECT job, called_on, calls FROM crawl_call_daily")
				.query(CrawlCallDaily.class)
				.list();
	}
}

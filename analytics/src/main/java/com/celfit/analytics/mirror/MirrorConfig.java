package com.celfit.analytics.mirror;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.AccountContentPoint;
import com.celfit.contract.analysis.AccountSummary;
import com.celfit.contract.analysis.Content;
import com.celfit.contract.analysis.ContentComment;
import com.celfit.contract.analysis.CrawlCallDaily;
import com.celfit.contract.analysis.LandingStats;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class MirrorConfig {

	@Bean
	public MirrorJob mirrorJob(JdbcTemplate rawJdbcTemplate,
			@Qualifier("analysisDataSource") DataSource analysisDataSource) {
		return new MirrorJob(rawJdbcTemplate, analysisDataSource);
	}

	/** 발굴 사전집계 matview 갱신기 — 입력 변경 잡 완료 시 AnalyticsJobService가 호출. */
	@Bean
	public DerivedViewRefresher derivedViewRefresher(
			@Qualifier("analysisDataSource") DataSource analysisDataSource) {
		return new DerivedViewRefresher(analysisDataSource);
	}

	/**
	 * 미러 대상 등록부 — 서빙 뷰 3종(B1) + 인플루언서 상세 2종(C1) + 랜딩 통계 1종(P3)
	 * + 크롤링 비용 1종(2026-08-13).
	 * 컬럼 계약은 각 record의 Javadoc과 V1·V10·V32 DDL 참조.
	 *
	 * <p>account_category_stats는 미러 대상이 아니다(07-21 V35) — 소스인 캡션 분류가 analysis DB에
	 * 있어 raw 뷰로는 만들 수 없다. analysis DB 안의 파생 뷰가 같은 이름·컬럼으로 대체한다.
	 *
	 * <p>content_metric_snapshots(v_content_metric_snapshots, V4)는 2026-07-30 등록부에서 뺐다 —
	 * 약 70만 행짜리 미러가 전체 미러 소요 12분 30초 중 6~7분을 차지했는데, 정작 이 미러 테이블을
	 * 읽는 소비처가 없었다(신선도 신호는 contents.metric_captured_at로 대체 — PipelineStatsService
	 * 참조). raw DB의 뷰·analysis DB의 테이블 자체는 남아 있다(테이블은 TRUNCATE만, DROP은 다음
	 * 릴리스 — V48 마이그레이션 참조).
	 */
	@Bean
	public MirrorRegistry mirrorRegistry() {
		return new MirrorRegistry(List.of(
				new MirrorSpec<>("v_accounts", "accounts", Account.class),
				new MirrorSpec<>("v_contents", "contents", Content.class),
				new MirrorSpec<>("v_content_comments", "content_comments", ContentComment.class),
				new MirrorSpec<>("v_account_summaries", "account_summaries", AccountSummary.class),
				new MirrorSpec<>("v_account_content_series", "account_content_series", AccountContentPoint.class),
				new MirrorSpec<>("v_landing_stats", "landing_stats", LandingStats.class),
				new MirrorSpec<>("v_crawl_call_daily", "crawl_call_daily", CrawlCallDaily.class)));
	}
}

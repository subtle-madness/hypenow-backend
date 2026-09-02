package com.celfit.analytics.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.analytics.testsupport.TestDb;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 매트릭스·타일 SQL을 실제 analysis 마이그레이션 스키마에 대고 검증한다 (행 0건이어도
 * 컬럼·테이블 이름 불일치는 여기서 걸린다). raw 쪽은 분석 뷰가 없는 DB를 가리키게 해
 * 조회 실패 시 우아한 축소(null)를 검증한다.
 *
 * <p>스키마는 매 테스트마다 재생성한다(AccountAnalysisJobTest·ClaudeBurstRunnerTest와 같은 관용구) —
 * 07-28 리뷰: 공유 @BeforeAll 스키마에 픽스처를 심고 try/finally로 수동 DELETE하던 방식은
 * 잔존 픽스처 리스크가 있었다. 매 테스트 전 재마이그레이션이라 "빈 스키마" 전제 테스트들과
 * 픽스처를 심는 테스트가 서로 격리된 채 공존한다.
 */
class CoverageRepositoryTest {

	static final PostgreSQLContainer pg = TestDb.shared();

	JdbcTemplate db;
	CoverageRepository repository;

	@BeforeEach
	void setUp() {
		DataSource ds = TestDb.rawDataSource(pg);
		db = new JdbcTemplate(ds);
		TestDb.resetAndMigrate(db, ds);
		// raw JdbcTemplate이 같은 DB를 가리키지만 analytics 뷰가 없다 — source()는 null로 축소돼야 한다
		repository = new CoverageRepository(db, ds);
	}

	@Test
	void 매트릭스는_마이그레이션_스키마에서_전행_집계된다() {
		List<CoverageRow> matrix = repository.matrix();

		assertThat(matrix).hasSize(28);
		assertThat(matrix.getFirst().element()).isEqualTo("계정 핸들·이름·프로필");
		// content_ranking은 개편 스키마에 없다 — 매트릭스는 죽지 않고 폴백 행으로 보고
		assertThat(matrix.getLast().status()).isEqualTo("개편 스키마 밖 — 정리 대상");
	}

	@Test
	void 타일은_빈_스키마에서_0으로_집계된다() {
		CoverageTiles tiles = repository.tiles();

		assertThat(tiles).isNotNull();
		assertThat(tiles.contents()).isZero();
	}

	@Test
	void 분석_뷰가_없으면_source는_null로_축소된다() {
		assertThat(repository.source()).isNull();
	}

	/**
	 * 27행("인플루언서 AI 리포트 카피") — "카피 완료"는 계정별 최신 행이 신 스키마(perf_summary,
	 * V40)를 보유할 때만이어야 한다. 구 스키마 최신 행(a1)만으로는 채움에 안 잡히고, 신 스키마
	 * 최신 행(a2)만 잡혀 분모 2 중 분자 1(부분)이 나와야 한다 — 07-28 드리프트 재발 방지.
	 * 이 테스트 전용 스키마(매 테스트 재생성)라 픽스처를 지울 필요가 없다.
	 */
	@Test
	void 매트릭스_27행은_최신_행이_신_스키마일_때만_카피_완료로_센다() {
		db.update("INSERT INTO accounts (handle) VALUES ('cov_a1'), ('cov_a2')");
		// a1: 구 스키마 최신 행(perf_summary NULL) — 카피 미완료로 세어야 한다
		db.update("""
				INSERT INTO account_analyses (handle, analyzed_at, model, tagline, summary)
				VALUES ('cov_a1', now(), 'm', '태그', '요약')""");
		// a2: 신 스키마 최신 행(perf_summary 있음) — 카피 완료
		db.update("""
				INSERT INTO account_analyses (handle, analyzed_at, model, tagline, perf_summary, content_summary)
				VALUES ('cov_a2', now(), 'm', '태그', '성과 요약', '콘텐츠 요약')""");

		CoverageRow row27 = repository.matrix().stream()
				.filter(r -> r.ord() == 27).findFirst().orElseThrow();
		assertThat(row27.filled()).isEqualTo("1 / 2");
		assertThat(row27.status()).isEqualTo("부분");
	}
}

package com.celfit.analytics.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.analytics.testsupport.TestDb;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 매트릭스·타일 SQL을 실제 analysis 마이그레이션 스키마에 대고 검증한다 (행 0건이어도
 * 컬럼·테이블 이름 불일치는 여기서 걸린다). raw 쪽은 분석 뷰가 없는 DB를 가리키게 해
 * 조회 실패 시 우아한 축소(null)를 검증한다.
 */
@Testcontainers
class CoverageRepositoryTest {

	@Container
	static PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine");

	static CoverageRepository repository;
	static JdbcTemplate db;

	@BeforeAll
	static void setUp() {
		DriverManagerDataSource ds = TestDb.rawDataSource(pg);
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
		assertThat(tiles.snapshotLatest()).isNull();
	}

	@Test
	void 분석_뷰가_없으면_source는_null로_축소된다() {
		assertThat(repository.source()).isNull();
	}

	/**
	 * 27행("인플루언서 AI 리포트 카피") — "카피 완료"는 계정별 최신 행이 신 스키마(perf_summary,
	 * V40)를 보유할 때만이어야 한다. 구 스키마 최신 행(a1)만으로는 채움에 안 잡히고, 신 스키마
	 * 최신 행(a2)만 잡혀 분모 2 중 분자 1(부분)이 나와야 한다 — 07-28 드리프트 재발 방지.
	 * @BeforeAll 공유 스키마를 건드리므로 끝에 픽스처를 지워 다른 테스트에 영향이 없게 한다.
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
		try {
			CoverageRow row27 = repository.matrix().stream()
					.filter(r -> r.ord() == 27).findFirst().orElseThrow();
			assertThat(row27.filled()).isEqualTo("1 / 2");
			assertThat(row27.status()).isEqualTo("부분");
		} finally {
			db.update("DELETE FROM account_analyses WHERE handle IN ('cov_a1', 'cov_a2')");
			db.update("DELETE FROM accounts WHERE handle IN ('cov_a1', 'cov_a2')");
		}
	}
}

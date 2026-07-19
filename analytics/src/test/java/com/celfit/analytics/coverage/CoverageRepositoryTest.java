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

	@BeforeAll
	static void setUp() {
		DriverManagerDataSource ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
		JdbcTemplate db = new JdbcTemplate(ds);
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
}

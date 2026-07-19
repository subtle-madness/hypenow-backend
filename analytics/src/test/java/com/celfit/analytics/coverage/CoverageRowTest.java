package com.celfit.analytics.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CoverageRowTest {

	@Test
	void 상태_문구가_등급으로_매핑된다() {
		assertThat(CoverageRow.of(1, "e", "s", "1 / 1", "준비됨").grade()).isEqualTo("ok");
		assertThat(CoverageRow.of(2, "e", "s", "1 / 2", "일부 누락").grade()).isEqualTo("warn");
		assertThat(CoverageRow.of(3, "e", "s", "1 / 67", "정상 범위").grade()).isEqualTo("ok");
		assertThat(CoverageRow.of(4, "e", "s", "0 / 137", "없음").grade()).isEqualTo("bad");
		assertThat(CoverageRow.of(5, "e", "s", "3 / 137", "부분").grade()).isEqualTo("warn");
		assertThat(CoverageRow.of(6, "e", "s", "2행", "옛 산출물 — 정리 대상").grade()).isEqualTo("warn");
		assertThat(CoverageRow.of(7, "e", "s", "테이블 없음", "개편 스키마 밖 — 정리 대상").grade()).isEqualTo("warn");
	}
}

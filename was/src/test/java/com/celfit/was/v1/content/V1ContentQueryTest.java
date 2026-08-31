package com.celfit.was.v1.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class V1ContentQueryTest {

	private static V1ContentQuery q(Integer limit, Integer offset) {
		return V1ContentQuery.of(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 28),
				null, null, null, null, null, null, null, null, null, limit, offset, null);
	}

	@Test
	void 같은_조건은_같은_캐시_키_페이지가_다르면_다른_키() {
		assertThat(q(50, 0).cacheKey()).isEqualTo(q(50, 0).cacheKey());
		assertThat(q(50, 0).cacheKey()).isNotEqualTo(q(50, 50).cacheKey());
	}

	@Test
	void 기본값_명시와_생략은_같은_키() {
		// contentType=reels·sort=hype 명시 == 생략(정규화 후 동일 조건)
		V1ContentQuery explicit = V1ContentQuery.of(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 28),
				"reels", null, null, null, null, null, null, null, "hype", 100, 0, null);
		assertThat(explicit.cacheKey()).isEqualTo(q(null, null).cacheKey());
	}

	@Test
	void 자유입력_리터럴_null은_필터_생략과_다른_키() {
		// keyword="null"(자유입력 리터럴)과 keyword 생략(실제 null)은 다른 조건 — 같은 캐시 키면 안 된다.
		V1ContentQuery literalNull = V1ContentQuery.of(LocalDate.of(2026, 7, 1),
				LocalDate.of(2026, 7, 28), null, null, null, null, null, "null", null, null, null,
				null, null, null);
		assertThat(literalNull.cacheKey()).isNotEqualTo(q(null, null).cacheKey());
	}

	@Test
	void 에스테틱_대분류는_검증을_통과한다() {
		// V20260809063533 시드로 추가된 어휘 — allowlist 누락 시 400 회귀 방지
		V1ContentQuery query = V1ContentQuery.of(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 28),
				null, "esthetic", null, null, null, null, null, null, null, null, null, null);
		assertThat(query.mainCategory()).isEqualTo("esthetic");
	}

	@Test
	void FnB_대분류_6종은_검증을_통과한다() {
		// 2026-08-31 F&B 서빙 개방 — allowlist 누락 시 400 회귀 방지 (에스테틱 테스트와 동일 취지)
		for (String main : java.util.List.of("beverage", "alcohol", "convenience", "snack",
				"health-food", "recipe")) {
			V1ContentQuery query = V1ContentQuery.of(LocalDate.of(2026, 8, 22), LocalDate.of(2026, 8, 27),
					null, main, null, null, null, null, null, null, null, null, null, null);
			assertThat(query.mainCategory()).isEqualTo(main);
		}
	}

	@Test
	void vertical은_beauty_fnb만_허용하고_캐시키에_들어간다() {
		// 2026-09-01 FE 버티컬 조회 요청 — F&B "전체" 탭이 한 번의 요청으로 축 전체를 받는다.
		V1ContentQuery fnb = V1ContentQuery.of(LocalDate.of(2026, 8, 22), LocalDate.of(2026, 8, 28),
				null, null, null, null, null, null, null, null, null, null, null, "fnb");
		assertThat(fnb.vertical()).isEqualTo("fnb");
		V1ContentQuery none = V1ContentQuery.of(LocalDate.of(2026, 8, 22), LocalDate.of(2026, 8, 28),
				null, null, null, null, null, null, null, null, null, null, null, null);
		assertThat(fnb.cacheKey()).isNotEqualTo(none.cacheKey()); // 캐시 오염 방지
		assertThatThrownBy(() -> V1ContentQuery.of(LocalDate.of(2026, 8, 22), LocalDate.of(2026, 8, 28),
				null, null, null, null, null, null, null, null, null, null, null, "food"))
				.hasMessageContaining("vertical");
	}

	@Test
	void vertical과_mainCategory_동시_지정은_400이다() {
		// 의미가 모호(교집합? 우선?)하므로 명시 거부 — FE가 실수를 빨리 잡게.
		assertThatThrownBy(() -> V1ContentQuery.of(LocalDate.of(2026, 8, 22), LocalDate.of(2026, 8, 28),
				null, "beverage", null, null, null, null, null, null, null, null, null, "fnb"))
				.hasMessageContaining("vertical");
	}

	@Test
	void midCategory_없이_subCategory만_보내면_400이다() {
		// 구 동작은 조용히 무시(계층 규칙) — FE가 빈 결과의 원인을 못 찾는 실수 유발이라 명시 거부로 변경(2026-09-01).
		assertThatThrownBy(() -> V1ContentQuery.of(LocalDate.of(2026, 8, 22), LocalDate.of(2026, 8, 28),
				null, "beverage", null, "기능성음료", null, null, null, null, null, null, null, null))
				.hasMessageContaining("subCategory");
	}

	@Test
	void next는_offset만_limit만큼_전진() {
		V1ContentQuery next = q(50, 0).next();
		assertThat(next.offset()).isEqualTo(50);
		assertThat(next.limit()).isEqualTo(50);
		assertThat(next.sort()).isEqualTo("hype");
	}
}

package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.was.v1.common.V1ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 브랜드 계정명 정규화·검증(스펙 §5-1 1단계) 순수 함수 단위 테스트. */
class BrandUsernameTest {

	@Test
	void 정규화는_골뱅이와_공백을_제거하고_소문자화한다() {
		assertThat(BrandUsername.normalize(" @Lizda_Official ")).isEqualTo("lizda_official");
	}

	@Test
	void 정규화는_null을_빈_문자열로_접는다() {
		// 본문 키 누락(username 없음)도 형식 위반 400으로 수렴시키기 위한 계약 — NPE로 500이 되면 안 된다.
		assertThat(BrandUsername.normalize(null)).isEmpty();
	}

	@Test
	void URL과_연속점과_금지문자는_거부한다() {
		for (String bad : List.of("https://www.instagram.com/lizda_official/", "a..b", "한글계정", "a b", "", "@",
				"instagram.com/lizda", "a".repeat(31))) {
			String normalized = BrandUsername.normalize(bad);
			assertThatThrownBy(() -> BrandUsername.validate(normalized))
					.isInstanceOf(V1ApiException.class)
					.satisfies(e -> assertThat(((V1ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
		}
	}

	@Test
	void 허용_문자만_담긴_계정명은_통과한다() {
		for (String ok : List.of("lizda_official", "a", "a.b", "brand.2026", "_", "a".repeat(30))) {
			assertThatCode(() -> BrandUsername.validate(BrandUsername.normalize(ok))).doesNotThrowAnyException();
		}
	}
}

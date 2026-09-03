package com.celfit.was.v1.influencer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * celfit-front src/lib/discover/group-purchase.ts 테스트 케이스 4종을 그대로 옮긴다 — 정규식
 * /공동구매|#공구/의 find() 매칭이 FE·BE 양쪽에서 동일해야 발굴 카드 뱃지와 상세 화면 판정이
 * 어긋나지 않는다.
 */
class GroupPurchaseSignalTest {

	@Test
	void 공동구매_본문_해시태그_매칭() {
		assertThat(GroupPurchaseSignal.matches("이번 공동구매 오픈했어요")).isTrue(); // 본문 어디든 매칭
		assertThat(GroupPurchaseSignal.matches("#공동구매 마감임박")).isTrue(); // 해시태그로 붙어도 매칭
	}

	@Test
	void 공구는_해시태그_접두_매칭만_인정() {
		assertThat(GroupPurchaseSignal.matches("#공구 링크는 프로필에")).isTrue();
		assertThat(GroupPurchaseSignal.matches("#공구오픈 #공구템 확인하세요")).isTrue(); // 접두 매칭 인정
	}

	@Test
	void 맨몸_공구나_무관한_문구는_비매칭() {
		assertThat(GroupPurchaseSignal.matches("메이크업 공구 정리했어요")).isFalse(); // # 없는 맨몸 "공구"
		assertThat(GroupPurchaseSignal.matches("데일리 루틴 공유")).isFalse();
	}

	@Test
	void 캡션_NULL은_미매칭() {
		assertThat(GroupPurchaseSignal.matches(null)).isFalse();
	}

	@Test
	void 목록_3건_중_2건_카운트() {
		List<String> captions = List.of("공동구매 오픈", "#공구오픈 링크", "데일리 루틴 공유");
		long count = captions.stream().filter(GroupPurchaseSignal::matches).count();
		assertThat(count).isEqualTo(2);
	}

	@Test
	void 빈_목록은_0건() {
		long count = List.<String>of().stream().filter(GroupPurchaseSignal::matches).count();
		assertThat(count).isZero();
	}
}

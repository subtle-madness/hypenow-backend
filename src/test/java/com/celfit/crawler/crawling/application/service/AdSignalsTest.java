package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AdSignalsTest {

    @Test
    void 광고_협찬_해시태그를_감지한다() {
        assertThat(AdSignals.adMarked(Map.of("caption", "오늘의 꿀템 #광고"))).isTrue();
        assertThat(AdSignals.adMarked(Map.of("caption", "#협찬 받았어요"))).isTrue();
        assertThat(AdSignals.adMarked(Map.of("caption", "review #AD thanks"))).isTrue();
        assertThat(AdSignals.adMarked(Map.of("caption", "#sponsored by brand"))).isTrue();
    }

    @Test
    void 협찬_문구를_감지한다() {
        assertThat(AdSignals.adMarked(Map.of("caption", "제작비 지원을 받아 작성했습니다"))).isTrue();
        assertThat(AdSignals.adMarked(Map.of("caption", "제품을 제공받아 사용해봤어요"))).isTrue();
        assertThat(AdSignals.adMarked(Map.of("caption", "유료 광고 포함"))).isTrue();
        assertThat(AdSignals.adMarked(Map.of("caption", "유료광고입니다"))).isTrue();
    }

    @Test
    void 일반_캡션은_광고가_아니다() {
        assertThat(AdSignals.adMarked(Map.of("caption", "내돈내산 후기예요"))).isFalse();
        // #ad는 단어 경계 필요 — adorable 오탐 방지
        assertThat(AdSignals.adMarked(Map.of("caption", "so #adorable today"))).isFalse();
        assertThat(AdSignals.adMarked(Map.of("caption", "광고 없는 솔직 후기"))).isFalse();
    }

    @Test
    void paidPartnership_필드가_참이면_광고다() {
        assertThat(AdSignals.adMarked(Map.of("paidPartnership", true, "caption", "일상"))).isTrue();
        assertThat(AdSignals.adMarked(Map.of("isPaidPartnership", true))).isTrue();
        assertThat(AdSignals.adMarked(Map.of("paidPartnership", false, "caption", "일상"))).isFalse();
    }

    @Test
    void 캡션이_없어도_안전하다() {
        assertThat(AdSignals.adMarked(Map.of())).isFalse();
    }
}

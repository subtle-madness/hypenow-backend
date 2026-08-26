package com.celfit.crawler.crawling.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 대시보드 중복 제거 그룹(③-3)의 count 쿼리 통합 테스트 — 두 축의 수집 대상을 겹침 없이
 * 나누는 규칙을 고정한다. 핵심 함정: "뷰티만"의 부정절을 NOT(fnb = true AND …)로 쓰면
 * F&B 미판정(fnb IS NULL)이 NULL 평가로 빠진다(설계 2026-08-25 §구현 주의점).
 */
class CollectableDedupCountIntegrationTest extends IntegrationTest {

    static final String PREFIX = "it-dedup-";

    @Autowired InfluencerRepository influencers;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("delete from influencer where username like ?", PREFIX + "%");
    }

    private Influencer seed(String name, Boolean beauty, Boolean beautyCompany,
                            Boolean fnb, Boolean fnbCompany) {
        Influencer inf = new Influencer(PREFIX + name);
        inf.setStatus(InfluencerStatus.QUALIFIED);
        inf.setBeauty(beauty);
        inf.setBeautyCompany(beautyCompany);
        inf.setFnb(fnb);
        inf.setFnbCompany(fnbCompany);
        return influencers.save(inf);
    }

    @Test
    void 수집_모수를_뷰티만_F앤B만_겹침으로_겹침_없이_나눈다() {
        seed("beauty-only-unjudged", true, null, null, null);   // 뷰티만 — F&B 미판정(NULL 함정)
        seed("beauty-only-notfnb", true, false, false, null);   // 뷰티만 — F&B 아님
        seed("beauty-only-fnbcorp", true, null, true, true);    // 뷰티만 — F&B는 회사라 수집 제외
        seed("fnb-only", false, null, true, null);              // F&B만 — 비뷰티
        seed("fnb-only-beautycorp", true, true, true, false);   // F&B만 — 뷰티는 회사라 수집 제외
        seed("both", true, false, true, false);                 // 겹침
        seed("neither-corp", true, true, true, true);           // 양쪽 다 회사 — 어디에도 안 잡힘
        seed("neither-unjudged", null, null, null, null);       // 양축 미판정 — 어디에도 안 잡힘

        assertThat(influencers.countBeautyOnlyCollectable(InfluencerStatus.QUALIFIED)).isEqualTo(3);
        assertThat(influencers.countFnbOnlyCollectable(InfluencerStatus.QUALIFIED)).isEqualTo(2);
        assertThat(influencers.countBothCollectable(InfluencerStatus.QUALIFIED)).isEqualTo(1);
    }

    @Test
    void QUALIFIED가_아니면_수집_모수에서_빠진다() {
        Influencer inf = seed("excluded", true, null, true, null);
        inf.setStatus(InfluencerStatus.EXCLUDED);
        influencers.save(inf);

        assertThat(influencers.countBothCollectable(InfluencerStatus.QUALIFIED)).isZero();
    }
}

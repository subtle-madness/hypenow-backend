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
 * 대시보드 중복 제거 그룹(③-3)의 count 쿼리 통합 테스트 — 세 축(뷰티·F&B·홈/리빙)의 수집
 * 대상을 겹침 없이 나누는 규칙을 고정한다. 핵심 함정: 단독 카운트의 부정절을
 * NOT(x = true AND …)로 쓰면 미판정(NULL)이 NULL 평가로 빠진다(설계 2026-08-25 §구현 주의점).
 * 겹침 타일은 별도 쿼리 없이 유니온(countAnyCollectable) − 단독 3합으로 계산한다.
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
                            Boolean fnb, Boolean fnbCompany,
                            Boolean homeLiving, Boolean homeLivingCompany) {
        Influencer inf = new Influencer(PREFIX + name);
        inf.setStatus(InfluencerStatus.QUALIFIED);
        inf.setBeauty(beauty);
        inf.setBeautyCompany(beautyCompany);
        inf.setFnb(fnb);
        inf.setFnbCompany(fnbCompany);
        inf.setHomeLiving(homeLiving);
        inf.setHomeLivingCompany(homeLivingCompany);
        return influencers.save(inf);
    }

    @Test
    void 수집_모수를_축별_단독과_겹침으로_겹침_없이_나눈다() {
        seed("beauty-only-unjudged", true, null, null, null, null, null);  // 뷰티만 — 다른 축 미판정(NULL 함정)
        seed("beauty-only-notother", true, false, false, null, false, null); // 뷰티만 — 다른 축 아님
        seed("beauty-only-othercorp", true, null, true, true, true, true); // 뷰티만 — 다른 축은 회사라 수집 제외
        seed("fnb-only", false, null, true, null, null, null);             // F&B만 — 비뷰티·홈/리빙 미판정
        seed("fnb-only-beautycorp", true, true, true, false, false, null); // F&B만 — 뷰티는 회사라 수집 제외
        seed("hl-only", false, null, null, null, true, null);              // 홈/리빙만 — F&B 미판정(NULL 함정)
        seed("hl-only-othercorp", true, true, true, true, true, false);    // 홈/리빙만 — 뷰티·F&B는 회사
        seed("both-beauty-fnb", true, false, true, false, null, null);     // 겹침(2축)
        seed("both-beauty-hl", true, false, false, null, true, false);     // 겹침(2축)
        seed("all-three", true, false, true, false, true, false);          // 겹침(3축)
        seed("neither-corp", true, true, true, true, true, true);          // 전부 회사 — 어디에도 안 잡힘
        seed("neither-unjudged", null, null, null, null, null, null);      // 전축 미판정 — 어디에도 안 잡힘

        long beautyOnly = influencers.countBeautyOnlyCollectable(InfluencerStatus.QUALIFIED);
        long fnbOnly = influencers.countFnbOnlyCollectable(InfluencerStatus.QUALIFIED);
        long hlOnly = influencers.countHomeLivingOnlyCollectable(InfluencerStatus.QUALIFIED);
        long any = influencers.countAnyCollectable(InfluencerStatus.QUALIFIED);

        assertThat(beautyOnly).isEqualTo(3);
        assertThat(fnbOnly).isEqualTo(2);
        assertThat(hlOnly).isEqualTo(2);
        assertThat(any).isEqualTo(10);
        // 겹침 = 유니온 − 단독 3합 — UiController ③-3 타일 계산식과 동일
        assertThat(any - beautyOnly - fnbOnly - hlOnly).isEqualTo(3);
    }

    @Test
    void QUALIFIED가_아니면_수집_모수에서_빠진다() {
        Influencer inf = seed("excluded", true, null, true, null, true, null);
        inf.setStatus(InfluencerStatus.EXCLUDED);
        influencers.save(inf);

        assertThat(influencers.countAnyCollectable(InfluencerStatus.QUALIFIED)).isZero();
    }
}

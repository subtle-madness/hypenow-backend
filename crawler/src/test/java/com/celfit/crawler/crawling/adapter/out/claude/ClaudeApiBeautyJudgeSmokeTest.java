package com.celfit.crawler.crawling.adapter.out.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.celfit.crawler.crawling.application.port.out.BeautyJudge.ProfileCard;
import com.celfit.crawler.crawling.application.port.out.BeautyJudge.Verdict;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

/**
 * 실 API 스모크 테스트 — ANTHROPIC_AUTH_TOKEN이 셸에 있을 때만 실행(평소·CI는 스킵).
 * 실행: ANTHROPIC_AUTH_TOKEN=sk-ant-oat01-... ./gradlew :crawler:test \
 *        --tests ClaudeApiBeautyJudgeSmokeTest
 * 구독 과금 검증은 실행 후 Console usage에 API 사용량이 안 찍히는 것으로 확인.
 */
class ClaudeApiBeautyJudgeSmokeTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_AUTH_TOKEN", matches = ".+")
    void 실_API로_두_계정을_판정한다() {
        ClaudeApiBeautyJudge judge = new ClaudeApiBeautyJudge(new ObjectMapper(), "claude-haiku-4-5");
        List<Verdict> verdicts = judge.judge(List.of(
                new ProfileCard("beauty_test_1", "뷰티 크리에이터", null,
                        "메이크업·스킨케어 리뷰 | 협찬 문의 DM", List.of("오늘의 쿠션 리뷰 💄")),
                new ProfileCard("football_test_1", "축구 하이라이트", null,
                        "매일 축구 영상 업로드", List.of("어제 경기 골 모음"))));
        assertEquals(2, verdicts.size());
        assertEquals(Set.of("beauty_test_1", "football_test_1"),
                Set.of(verdicts.get(0).username(), verdicts.get(1).username()));
        Verdict beauty = verdicts.stream()
                .filter(v -> v.username().equals("beauty_test_1")).findFirst().orElseThrow();
        assertTrue(beauty.beauty(), "뷰티 계정이 뷰티로 판정돼야 함 — reason: " + beauty.reason());
    }
}

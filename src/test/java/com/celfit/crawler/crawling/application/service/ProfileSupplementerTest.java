package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.settings.application.service.ProfileSourceSettingTest;
import com.celfit.crawler.settings.application.service.ProfileSupplementSetting;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProfileSupplementerTest {

    ObjectMapper om = new ObjectMapper();

    ProfileSupplementSetting settingBoth() {
        var s = new ProfileSupplementSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        s.update(true, true);
        return s;
    }

    CrawlExecutor.Execution oneItem() {
        Map<String, Object> item = new HashMap<>(Map.of("username", "tem.duck", "followersCount", 1L, "userId", "999"));
        return new CrawlExecutor.Execution(1L, List.of(item));
    }

    @Test void ACTOR는_보충_안함() {
        HikerHttp http = p -> { throw new AssertionError("호출되면 안됨"); };
        var sup = new ProfileSupplementer(new HikerMediasSupplement(http, om),
                new HikerSuggestedSupplement(http, om), settingBoth());
        var ex = sup.apply(oneItem(), ProfileSource.ACTOR);
        assertThat(ex.items().get(0)).doesNotContainKey("latestPosts");
    }

    @Test void SELF_둘다_보충() {
        HikerHttp http = path -> path.contains("medias")
            ? "{\"response\":{\"items\":[{\"code\":\"X\",\"play_count\":10}]}}"
            : "{\"users\":[{\"username\":\"my_zipcode\",\"pk\":\"1\"}]}";
        var sup = new ProfileSupplementer(new HikerMediasSupplement(http, om),
                new HikerSuggestedSupplement(http, om), settingBoth());
        var ex = sup.apply(oneItem(), ProfileSource.SELF);
        assertThat(ex.items().get(0))
            .containsKeys("latestPosts", "relatedProfiles", "_rawMedias", "_rawSuggested");  // 정규화 + 원본
    }

    @Test void medias_튜플응답_파싱() {
        // 실제 HikerAPI /v1/user/medias/chunk 형태: [[...medias...], "cursor"]
        HikerHttp http = path -> path.contains("medias")
            ? "[[{\"code\":\"ABC\",\"play_count\":587,\"like_count\":51,\"comment_count\":128}],\"next_cursor\"]"
            : "{\"users\":[]}";
        var sup = new ProfileSupplementer(new HikerMediasSupplement(http, om),
                new HikerSuggestedSupplement(http, om), settingBoth());
        var ex = sup.apply(oneItem(), ProfileSource.SELF);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> posts = (List<Map<String, Object>>) ex.items().get(0).get("latestPosts");
        assertThat(posts).hasSize(1);
        assertThat(posts.get(0))
            .containsEntry("shortCode", "ABC")
            .containsEntry("videoViewCount", 587L)
            .containsEntry("likesCount", 51L)
            .containsEntry("commentsCount", 128L);
    }

    @Test void 한_보충_실패해도_나머지와_베이스는_보존() {
        HikerHttp http = path -> {
            if (path.contains("medias")) throw new ApifyException("Hiker HTTP 500");
            return "{\"users\":[{\"username\":\"my_zipcode\",\"pk\":\"1\"}]}";
        };
        var sup = new ProfileSupplementer(new HikerMediasSupplement(http, om),
                new HikerSuggestedSupplement(http, om), settingBoth());
        var ex = sup.apply(oneItem(), ProfileSource.HIKER_MOBILE);
        Map<String, Object> item = ex.items().get(0);
        assertThat(item).doesNotContainKey("latestPosts");     // medias 실패 → 없음
        assertThat(item).containsKey("relatedProfiles");        // related 성공
        assertThat(item.get("username")).isEqualTo("tem.duck"); // 베이스 보존
    }
}

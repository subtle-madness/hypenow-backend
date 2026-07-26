package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.settings.application.service.ProfileSourceSettingTest;
import com.celfit.crawler.settings.application.service.ProfileSupplementSetting;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProfileSupplementerTest {

    ObjectMapper om = new ObjectMapper();

    ProfileSupplementSetting settingRelated(boolean enabled) {
        var s = new ProfileSupplementSetting(ProfileSourceSettingTest.fakeRepo(new HashMap<>()));
        s.update(enabled);
        return s;
    }

    /** SELF_GQL 원형: {"data":{"user":{"username":..., "id":...}}} */
    static Map<String, Object> selfItem() {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", "tem.duck");
        user.put("id", "999");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user", user);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("data", data);
        return root;
    }

    /** HIKER_MOBILE 원형: {"user":{"username":..., "pk":...}} */
    static Map<String, Object> hikerMobileItem() {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", "tem.duck");
        user.put("pk", "999");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("user", user);
        return root;
    }

    static CrawlExecutor.Execution execWith(Map<String, Object> item) {
        return new CrawlExecutor.Execution(1L, List.of(item));
    }

    @Test void ACTOR는_보충_안함() {
        HikerHttp http = p -> { throw new AssertionError("호출되면 안됨"); };
        var sup = new ProfileSupplementer(new HikerSuggestedSupplement(http, om), settingRelated(true));
        var ex = sup.apply(execWith(selfItem()), ProfileSource.ACTOR);
        assertThat(ex.items().get(0)).doesNotContainKey("relatedProfiles");
    }

    @Test void 토글이_꺼져있으면_보충_안함() {
        HikerHttp http = p -> { throw new AssertionError("호출되면 안됨"); };
        var sup = new ProfileSupplementer(new HikerSuggestedSupplement(http, om), settingRelated(false));
        var ex = sup.apply(execWith(selfItem()), ProfileSource.SELF);
        assertThat(ex.items().get(0)).doesNotContainKey("relatedProfiles");
    }

    @Test void SELF_원형에서_userId를_추출해_related를_보충한다() {
        HikerHttp http = path -> {
            assertThat(path).contains("user_id=999");
            return "{\"users\":[{\"username\":\"my_zipcode\",\"pk\":\"1\"}]}";
        };
        var sup = new ProfileSupplementer(new HikerSuggestedSupplement(http, om), settingRelated(true));
        var ex = sup.apply(execWith(selfItem()), ProfileSource.SELF);
        assertThat(ex.items().get(0)).containsKeys("relatedProfiles", "_rawSuggested");
    }

    @Test void HIKER_MOBILE_원형에서_userId를_추출해_related를_보충한다() {
        HikerHttp http = path -> {
            assertThat(path).contains("user_id=999");
            return "{\"users\":[{\"username\":\"my_zipcode\",\"pk\":\"1\"}]}";
        };
        var sup = new ProfileSupplementer(new HikerSuggestedSupplement(http, om), settingRelated(true));
        var ex = sup.apply(execWith(hikerMobileItem()), ProfileSource.HIKER_MOBILE);
        assertThat(ex.items().get(0)).containsKeys("relatedProfiles", "_rawSuggested");
    }

    @Test void SELF_HIKER_FALLBACK_혼합_배치는_아이템별_감지로_보충한다() {
        java.util.List<String> paths = new java.util.ArrayList<>();
        HikerHttp http = path -> {
            paths.add(path);
            return "{\"users\":[{\"username\":\"my_zipcode\",\"pk\":\"1\"}]}";
        };
        var sup = new ProfileSupplementer(new HikerSuggestedSupplement(http, om), settingRelated(true));

        var ex = new CrawlExecutor.Execution(1L, List.of(selfItem(), hikerMobileItem()));
        sup.apply(ex, ProfileSource.SELF_HIKER_FALLBACK);

        // SELF 원형(data.user.id=999)과 HIKER 원형(user.pk=999) 모두 userId가 추출돼 보충된다
        assertThat(ex.items().get(0)).containsKeys("relatedProfiles", "_rawSuggested");
        assertThat(ex.items().get(1)).containsKeys("relatedProfiles", "_rawSuggested");
        assertThat(paths).hasSize(2).allSatisfy(p -> assertThat(p).contains("user_id=999"));
    }

    @Test void related_보충_실패해도_베이스_원형은_보존된다() {
        HikerHttp http = path -> { throw new ApifyException("Hiker HTTP 500"); };
        var sup = new ProfileSupplementer(new HikerSuggestedSupplement(http, om), settingRelated(true));
        var ex = sup.apply(execWith(hikerMobileItem()), ProfileSource.HIKER_MOBILE);
        Map<String, Object> item = ex.items().get(0);
        assertThat(item).doesNotContainKey("relatedProfiles");
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) item.get("user");
        assertThat(user.get("username")).isEqualTo("tem.duck"); // 베이스 원형 보존
    }
}

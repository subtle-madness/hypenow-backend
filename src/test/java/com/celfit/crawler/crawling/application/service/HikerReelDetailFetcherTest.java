package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import com.celfit.crawler.crawling.application.port.out.ApifyException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HikerReelDetailFetcherTest {

    ObjectMapper om = new ObjectMapper();
    DetailMapper mapper = new DetailMapper(om);

    @Test void 각_shortCode마다_media_info_호출하고_정규화() {
        HikerHttp http = path -> "{\"code\":\"" + path.substring(path.indexOf("code=") + 5)
                + "\",\"like_count\":10,\"comment_count\":2,\"play_count\":100}";
        var f = new HikerReelDetailFetcher(http, null, mapper);
        List<Map<String, Object>> out = f.collect(List.of("AA", "BB"), new java.util.LinkedHashSet<>());
        assertThat(out).hasSize(2);
        assertThat(out.get(0)).containsEntry("shortCode", "AA").containsEntry("videoPlayCount", 100L);
    }

    @Test void 한_shortCode_실패시_failed집합에_담기고_나머지_보존() {
        HikerHttp http = path -> {
            if (path.contains("code=BAD")) throw new ApifyException("Hiker HTTP 404");
            return "{\"code\":\"OK\",\"like_count\":1}";
        };
        var f = new HikerReelDetailFetcher(http, null, mapper);
        var failed = new java.util.LinkedHashSet<String>();
        List<Map<String, Object>> out = f.collect(List.of("BAD", "OK"), failed);
        assertThat(out).hasSize(1);
        assertThat(out.get(0)).containsEntry("shortCode", "OK");
        assertThat(failed).containsExactly("BAD");
    }
}

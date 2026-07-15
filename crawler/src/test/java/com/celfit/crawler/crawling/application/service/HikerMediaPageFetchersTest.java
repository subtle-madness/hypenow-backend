package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.crawling.adapter.out.hiker.HikerHttp;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HikerMediaPageFetchersTest {

    static class FakeHttp implements HikerHttp {
        String lastPath;
        String response = "{\"items\":[{\"code\":\"A\"}],\"more_available\":true,\"profile_grid_items_cursor\":\"C1\"}";
        @Override public String get(String path) { lastPath = path; return response; }
    }

    @Test
    void gql_medias는_flat과_커서를_붙여_호출하고_원형을_반환한다() {
        FakeHttp http = new FakeHttp();
        HikerGqlMediasFetcher f = new HikerGqlMediasFetcher(http, new ObjectMapper());

        Map<String, Object> first = f.fetchPage("74969123775", null);
        assertThat(http.lastPath).isEqualTo("/gql/user/medias?user_id=74969123775&flat=true");
        assertThat(first).containsKey("items").containsEntry("profile_grid_items_cursor", "C1");

        f.fetchPage("74969123775", "QVF+D/x=");
        assertThat(http.lastPath)
                .isEqualTo("/gql/user/medias?user_id=74969123775&flat=true&profile_grid_items_cursor=QVF%2BD%2Fx%3D");
    }

    @Test
    void v2_clips는_page_id_커서로_호출한다() {
        FakeHttp http = new FakeHttp();
        http.response = "{\"response\":{\"items\":[]},\"next_page_id\":\"P2\"}";
        HikerV2ClipsFetcher f = new HikerV2ClipsFetcher(http, new ObjectMapper());

        f.fetchPage("8558856783", null);
        assertThat(http.lastPath).isEqualTo("/v2/user/clips?user_id=8558856783");
        f.fetchPage("8558856783", "P2");
        assertThat(http.lastPath).isEqualTo("/v2/user/clips?user_id=8558856783&page_id=P2");
    }
}

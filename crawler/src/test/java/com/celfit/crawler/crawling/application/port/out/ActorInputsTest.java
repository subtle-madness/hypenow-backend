package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.domain.ShortCodes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActorInputsTest {

    @Test
    void 한글_키워드는_keywordSearch가_켜진다() {
        assertThat(ActorInputs.needsKeywordSearch("메이크업")).isTrue();
        assertThat(ActorInputs.needsKeywordSearch("makeup")).isFalse();
    }

    @Test
    void discovery_입력() {
        Map<String, Object> input = ActorInputs.discovery("메이크업", 100);
        assertThat(input)
                .containsEntry("hashtags", List.of("메이크업"))
                .containsEntry("resultsType", "posts")
                .containsEntry("resultsLimit", 100)
                .containsEntry("keywordSearch", true);
    }

    @Test
    void detailUrls_comments_profiles_입력() {
        List<String> urls = List.of("https://www.instagram.com/p/abc/");
        assertThat(ActorInputs.detailUrls(urls))
                .containsEntry("username", urls);
        assertThat(ActorInputs.comments(urls, 50))
                .containsEntry("directUrls", urls)
                .containsEntry("resultsLimit", 50);
        assertThat(ActorInputs.profiles(List.of("kim")))
                .containsEntry("usernames", List.of("kim"));
    }

    @Test
    void reels_입력은_username_배열과_결과_한도를_담는다() {
        Map<String, Object> input = ActorInputs.reels("alice", 6);
        assertThat(input).containsEntry("username", List.of("alice"))
                .containsEntry("resultsLimit", 6);
    }

    @Test
    void chunk는_n개_단위로_쪼갠다() {
        assertThat(ActorInputs.chunk(List.of(1, 2, 3, 4, 5), 2))
                .containsExactly(List.of(1, 2), List.of(3, 4), List.of(5));
        assertThat(ActorInputs.chunk(List.of(), 2)).isEmpty();
    }

    @Test
    void shortCode_url_왕복() {
        assertThat(ShortCodes.postUrl("abc_-1")).isEqualTo("https://www.instagram.com/p/abc_-1/");
        assertThat(ShortCodes.reelUrl("abc_-1")).isEqualTo("https://www.instagram.com/reel/abc_-1/");
        assertThat(ShortCodes.fromUrl("https://www.instagram.com/p/abc_-1/")).contains("abc_-1");
        assertThat(ShortCodes.fromUrl("https://www.instagram.com/reel/xyz9/?hl=ko")).contains("xyz9");
        assertThat(ShortCodes.fromUrl("https://example.com/nope")).isEmpty();
    }
}

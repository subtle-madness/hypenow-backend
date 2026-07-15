package com.celfit.crawler.crawling.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CommentMapperTest extends IntegrationTest {

    @Autowired CommentMapper mapper;

    String fixture() throws Exception {
        return new String(getClass().getResourceAsStream("/instagram/comments-response.json").readAllBytes());
    }

    @Test
    void 응답을_스키마호환_댓글맵으로_변환한다() throws Exception {
        var page = mapper.parse(fixture(), "https://www.instagram.com/p/AA/");
        // 픽스처엔 댓글 15개, 단일 페이지(has_next_page=false, end_cursor=null)
        assertThat(page.comments()).hasSize(15);
        assertThat(page.hasNext()).isFalse();
        assertThat(page.endCursor()).isNull();
        var first = page.comments().get(0);
        assertThat(first).containsKeys("postUrl", "ownerUsername", "text", "timestamp");
        assertThat(first.get("postUrl")).isEqualTo("https://www.instagram.com/p/AA/");
        assertThat(first.get("ownerUsername")).isEqualTo("songsariiiii");
        assertThat(first.get("text")).isEqualTo("이정도는 기본아잉교 ❤️");
        // created_at 1779661498(epoch초) → ISO-8601 UTC 문자열
        assertThat(first.get("timestamp")).isEqualTo("2026-05-24T22:24:58.000Z");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 액터와_동일한_전체_payload_스키마로_채운다() throws Exception {
        var page = mapper.parse(fixture(), "https://www.instagram.com/p/AA/");
        var first = page.comments().get(0);

        // 액터 raw payload와 동일한 top-level 키 집합 (11개)
        assertThat(first).containsOnlyKeys(
                "id", "text", "owner", "postUrl", "replies", "timestamp",
                "commentUrl", "likesCount", "repliesCount", "ownerUsername", "ownerProfilePicUrl");
        assertThat(first.get("id")).isEqualTo("18108559372934377");
        assertThat(first.get("commentUrl"))
                .isEqualTo("https://www.instagram.com/p/AA/c/18108559372934377");
        assertThat(first.get("likesCount")).isEqualTo(1);      // comment_like_count
        assertThat(first.get("repliesCount")).isNull();         // 로그아웃 응답은 child_comment_count=null
        assertThat(first.get("replies")).isNull();              // 액터도 비로그인이라 null
        assertThat(first.get("ownerProfilePicUrl")).isEqualTo(
                "https://scontent-ssn1-1.cdninstagram.com/v/t51.82787-19/694948777_18578706688030156_5125196766208278863_n.jpg");

        // 중첩 owner 객체 — 액터와 동일 키 (fbid_v2는 자체 응답에 없어 null)
        var owner = (Map<String, Object>) first.get("owner");
        assertThat(owner).containsOnlyKeys(
                "id", "fbid_v2", "username", "full_name", "is_private",
                "is_verified", "is_mentionable", "profile_pic_id", "profile_pic_url", "latest_reel_media");
        assertThat(owner.get("id")).isEqualTo("760734155");
        assertThat(owner.get("username")).isEqualTo("songsariiiii");
        assertThat(owner.get("is_verified")).isEqualTo(false);
        assertThat(owner.get("fbid_v2")).isNull();
        assertThat(owner.get("full_name")).isNull();
    }
}

package com.celfit.crawler.crawling.application.port.out;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 액터 입력 생성 순수 헬퍼. */
public final class ActorInputs {

    /**
     * 인스타 비로그인 해시태그 페이지는 한글(비ASCII) 태그를 차단해 hashtag 모드가
     * 빈 결과를 반환한다 → 액터의 키워드 검색 모드로 우회.
     */
    public static boolean needsKeywordSearch(String keyword) {
        return keyword != null && keyword.chars().anyMatch(c -> c > 0x7f);
    }

    public static Map<String, Object> discovery(String keyword, int resultsLimit) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("hashtags", List.of(keyword));
        input.put("resultsType", "posts");
        input.put("resultsLimit", resultsLimit);
        input.put("keywordSearch", needsKeywordSearch(keyword));
        return input;
    }

    /** reel/post 전용 상세 액터 입력 — 게시물 URL 배열을 username 필드로 받는다. */
    public static Map<String, Object> detailUrls(List<String> postUrls) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("username", postUrls);
        return input;
    }

    /** reel 전용 상세 액터의 계정 열거 모드 — username 배열 + 계정당 결과 한도(건수 과금). */
    public static Map<String, Object> reels(String username, int resultsLimit) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("username", List.of(username));
        input.put("resultsLimit", resultsLimit);
        return input;
    }

    public static Map<String, Object> comments(List<String> postUrls, int perPost) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("directUrls", postUrls);
        input.put("resultsLimit", perPost);
        return input;
    }

    public static Map<String, Object> profiles(List<String> usernames) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("usernames", usernames);
        return input;
    }

    public static <T> List<List<T>> chunk(List<T> list, int n) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += n) {
            out.add(List.copyOf(list.subList(i, Math.min(i + n, list.size()))));
        }
        return out;
    }

    private ActorInputs() {}
}

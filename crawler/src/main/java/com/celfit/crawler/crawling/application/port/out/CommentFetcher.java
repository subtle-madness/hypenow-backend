package com.celfit.crawler.crawling.application.port.out;

import com.celfit.crawler.crawling.domain.RawSource;
import com.celfit.crawler.crawling.domain.TriggerType;
import com.celfit.crawler.settings.domain.CommentSource;
import java.util.List;
import java.util.Map;

/** 청크(포스트 여러 개)의 댓글 수집. 청크 전체를 crawl_run 1건으로 감싼다. */
public interface CommentFetcher {

    /**
     * shortCode → 페이지 원형 목록. SELF는 GraphQL 페이지 응답 JSON을 그대로 담고,
     * ACTOR는 댓글 아이템 하나하나를 그대로 담는다 — 값의 해석은 소스(rawSource)가 구분한다.
     */
    record CommentResult(Long runId, Map<String, List<Map<String, Object>>> pagesByCode) {}

    CommentResult fetch(List<String> shortCodes, int commentsPerPost, TriggerType trigger);

    CommentSource source();

    /** pagesByCode 값의 원형 형태를 나타내는 RawSource — RawComment 저장에 사용. */
    RawSource rawSource();
}

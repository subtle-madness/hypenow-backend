package com.celfit.crawler.content.application.port.out;

import java.util.List;
import java.util.Optional;

import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentOrigin;
import com.celfit.crawler.content.domain.ContentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentRepository extends JpaRepository<Content, Long> {

    Optional<Content> findByShortCode(String shortCode);

    /** upsert 일괄 조회 — 방문당 게시물 N개를 개별 조회(N왕복) 대신 IN 1회로 가져온다. */
    java.util.List<Content> findByShortCodeIn(java.util.Collection<String> shortCodes);

    List<Content> findByStatus(ContentStatus status);

    Page<Content> findByStatus(ContentStatus status, Pageable pageable);

    /** collect 댓글 대상 조회 — origin=ENUMERATION(열거 산출)만 수집 대상이다. 발굴 부산물은 제외. */
    List<Content> findByInfluencerIdAndStatusAndOrigin(Long influencerId, ContentStatus status, ContentOrigin origin);

    /** 대시보드 게시물 수집 카드 — origin=ENUMERATION 기준으로만 집계한다. */
    long countByStatusAndOrigin(ContentStatus status, ContentOrigin origin);

    /** 대시보드 게시물 유형별(FEED/REELS) 카드 — origin=ENUMERATION 기준. */
    long countByOriginAndContentType(ContentOrigin origin, com.celfit.crawler.content.domain.ContentType contentType);

    /** 발굴 보관(부산물) 총계 — 수집 대상 아님, 대시보드에 참고용으로만 표시. */
    long countByOrigin(ContentOrigin origin);

    /** 데일리 대시보드: 기준 시각(오늘 자정) 이후 처음 발견된 수집 게시물 수. */
    long countByOriginAndFirstSeenAtGreaterThanEqual(ContentOrigin origin, java.time.Instant since);

    /** 데일리 대시보드: 오늘 처음 발견된 수집 게시물의 유형별(FEED/REELS) 수. */
    long countByOriginAndContentTypeAndFirstSeenAtGreaterThanEqual(
            ContentOrigin origin, com.celfit.crawler.content.domain.ContentType contentType, java.time.Instant since);
}

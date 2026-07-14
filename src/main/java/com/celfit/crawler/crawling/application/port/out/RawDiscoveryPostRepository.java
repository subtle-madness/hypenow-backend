package com.celfit.crawler.crawling.application.port.out;

import java.util.List;

import com.celfit.crawler.crawling.domain.RawDiscoveryPost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RawDiscoveryPostRepository extends JpaRepository<RawDiscoveryPost, Long> {

    /** DISCOVER run별 발굴 통계 프로젝션. */
    interface RunDiscoveryStat {
        Long getRunId();
        long getTotal();
        long getDuplicates();  // 이전에 이미 발굴됐던 게시물(중복 재발굴) 수
    }

    /**
     * 주어진 run들 각각에 대해 총 발굴 수와, 그 중 "이미 발굴됐던 게시물"(같은 content를
     * 더 이른 raw_discovery_post가 이미 기록한 경우 = 중복 재발굴) 수를 센다.
     * 신규 = total - duplicates.
     */
    @Query("""
        SELECT r.crawlRunId AS runId, COUNT(r) AS total,
               SUM(CASE WHEN EXISTS (SELECT e FROM RawDiscoveryPost e
                                     WHERE e.contentId = r.contentId AND e.id < r.id)
                        THEN 1L ELSE 0L END) AS duplicates
        FROM RawDiscoveryPost r
        WHERE r.crawlRunId IN :runIds
        GROUP BY r.crawlRunId
        """)
    List<RunDiscoveryStat> discoveryStats(@Param("runIds") List<Long> runIds);
}

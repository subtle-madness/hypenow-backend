package com.celfit.crawler.crawling.application.port.out;

import java.util.List;

import com.celfit.crawler.crawling.domain.RawDiscoveryPost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RawDiscoveryPostRepository extends JpaRepository<RawDiscoveryPost, Long> {

    /** DISCOVER run별 발굴 통계 프로젝션 — 게시물이 아니라 인플루언서 기준. */
    interface RunDiscoveryStat {
        Long getRunId();
        long getInfluencers();       // 이 run의 발굴 게시물 작성자(인플루언서) 수 (distinct)
        long getKnownInfluencers();  // 그중 run 시작 전에 이미 발굴돼 있던 인플루언서(중복 재발굴) 수
    }

    /**
     * 주어진 run들 각각에 대해 발굴된 인플루언서 수와, 그중 "이미 발굴됐던 인플루언서"
     * (해당 run 시작 전의 influencer_discovery 이력이 존재) 수를 센다. 신규 = influencers - known.
     * run 귀속은 raw_discovery_post(중복 발굴이어도 항상 저장) → content 조인으로 얻는다.
     */
    @Query(value = """
        SELECT p.crawl_run_id AS "runId",
               COUNT(DISTINCT c.influencer_id) AS "influencers",
               COUNT(DISTINCT c.influencer_id) FILTER (
                   WHERE EXISTS (
                       SELECT 1 FROM influencer_discovery d
                       WHERE d.influencer_id = c.influencer_id
                         AND d.discovered_at < r.started_at)) AS "knownInfluencers"
        FROM raw_discovery_post p
        JOIN content c ON c.id = p.content_id
        JOIN crawl_run r ON r.id = p.crawl_run_id
        WHERE p.crawl_run_id IN (:runIds)
        GROUP BY p.crawl_run_id
        """, nativeQuery = true)
    List<RunDiscoveryStat> discoveryStats(@Param("runIds") List<Long> runIds);
}

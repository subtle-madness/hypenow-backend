package com.celfit.crawler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 액터 응답 아이템 전량의 verbatim 아카이브. 파이프라인이 어디서 무엇을 버리든
 * (규칙 탈락·매칭 실패 등) 과금된 응답은 여기에 전부 남는다.
 */
@Entity
@Table(name = "raw_run_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RawRunItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "crawl_run_id", nullable = false)
    private Long crawlRunId;

    @Column(name = "item_index", nullable = false)
    private int itemIndex;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    public RawRunItem(Long crawlRunId, int itemIndex, Map<String, Object> payload) {
        this.crawlRunId = crawlRunId;
        this.itemIndex = itemIndex;
        this.payload = payload;
    }
}

package com.celfit.crawler.content.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 발굴 입력 키워드 — 분류 계층 없음. 이름 수정 없음(수정 = 삭제 + 추가). */
@Entity
@Table(name = "search_keyword")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SearchKeyword {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String keyword;

    @Setter
    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public SearchKeyword(String keyword, Instant createdAt) {
        this.keyword = keyword;
        this.createdAt = createdAt;
    }
}

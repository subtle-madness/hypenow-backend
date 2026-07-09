package com.celfit.crawler.content.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "category_keyword")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CategoryKeyword {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    /** 소분류 — 해시태그 검색어 그 자체. */
    @Column(nullable = false)
    private String keyword;

    /** 중분류 — 지정 없으면 키워드 자신. */
    @Column(nullable = false)
    private String subcategory;

    /** 대분류 — 지정 없으면 중분류 값. 계층: 카테고리 > 대분류 > 중분류 > 소분류(keyword). */
    @Column(name = "main_group", nullable = false)
    private String mainGroup;

    @Column(nullable = false)
    private boolean enabled = true;

    public CategoryKeyword(Long categoryId, String keyword) {
        this(categoryId, keyword, keyword, keyword);
    }

    public CategoryKeyword(Long categoryId, String keyword, String subcategory) {
        this(categoryId, keyword, subcategory, subcategory);
    }

    public CategoryKeyword(Long categoryId, String keyword, String subcategory, String mainGroup) {
        this.categoryId = categoryId;
        this.keyword = keyword;
        this.subcategory = subcategory;
        this.mainGroup = mainGroup;
    }
}

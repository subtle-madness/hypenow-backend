package com.celfit.crawler.domain;

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

    @Column(nullable = false)
    private String keyword;

    @Column(nullable = false)
    private boolean enabled = true;

    public CategoryKeyword(Long categoryId, String keyword) {
        this.categoryId = categoryId;
        this.keyword = keyword;
    }
}

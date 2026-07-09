package com.celfit.crawler.content.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "collection_rule")
@Getter @Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CollectionRule {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "category_id", nullable = false, unique = true)
    private Long categoryId;

    @Column(name = "min_followers")
    private Integer minFollowers;

    @Column(name = "max_followers")
    private Integer maxFollowers;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_types", nullable = false)
    private ContentTypeFilter contentTypes = ContentTypeFilter.ALL;

    public CollectionRule(Long categoryId) {
        this.categoryId = categoryId;
    }

    /** 팔로워 조건이 하나라도 있으면 프로필 데이터 없이는 판정 불가. */
    public boolean needsFollowers() {
        return minFollowers != null || maxFollowers != null;
    }

    public boolean followersPass(long followers) {
        return (minFollowers == null || followers >= minFollowers)
                && (maxFollowers == null || followers <= maxFollowers);
    }
}

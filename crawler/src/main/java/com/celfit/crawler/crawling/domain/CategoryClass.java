package com.celfit.crawler.crawling.domain;

/**
 * 카테고리 공용 5분류 — F&B 축(fnb_class)의 저장값이자 파생 boolean(fnb/fnb_company) 규칙의
 * 단일 원천 (스펙 2026-08-23 §1). 뷰티 축은 역사적 이름(BeautyClass — BEAUTY_SERVICE·NOT_BEAUTY)이
 * 운영 DB에 박혀 있어 그대로 두고, 새 카테고리 축부터 이 중립 이름을 쓴다.
 */
public enum CategoryClass {
    /** 해당 카테고리 제품 콘텐츠 중심 한국어 개인 크리에이터 — 시딩·협찬 타깃. */
    INFLUENCER,
    /** 해당 카테고리 제품 제작·판매 회사(브랜드·쇼핑몰, 언어 무관) — 컨택 타깃. */
    COMPANY,
    /** 매장·서비스 공식 계정(F&B: 식당·카페·베이커리 등 업장 자체) — 타깃 아님. */
    SERVICE,
    /** 개인 크리에이터지만 한국어 콘텐츠가 아님 — 한국 시장 시딩 타깃 아님. */
    FOREIGN_INFLUENCER,
    /** 해당 카테고리 콘텐츠 중심이 아닌 계정. */
    NONE;

    public boolean inCategory() {
        return this == INFLUENCER || this == COMPANY;
    }

    public boolean company() {
        return this == COMPANY;
    }
}

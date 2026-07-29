package com.celfit.crawler.crawling.domain;

/**
 * 뷰티 판정 5분류 (v3, 2026-07-28 스펙) — 판정 목적은 뷰티 제품(스킨케어·메이크업·향수 등)
 * 한국 시장 시딩·협찬 대상 발굴. boolean(beauty/beauty_company) 파생 규칙의 단일 원천.
 * BEAUTY_SERVICE·FOREIGN_INFLUENCER는 beauty=false로 파생 — 시드·수집·서빙 모수에서 자동 제외된다.
 */
public enum BeautyClass {
    /** 한국어 콘텐츠 중심 뷰티 제품 개인 크리에이터 — 시딩·협찬 타깃. */
    INFLUENCER,
    /** 뷰티 제품 제작·판매 회사(브랜드·쇼핑몰, 언어 무관) — 컨택 타깃. */
    COMPANY,
    /** 뷰티 영역이지만 시술·서비스 중심(병원·에스테틱·헤어·네일 업체와 그 영역 개인) — 타깃 아님. */
    BEAUTY_SERVICE,
    /** 뷰티 제품 개인 크리에이터지만 한국어 콘텐츠가 아님(v3) — 한국 시장 시딩 타깃 아님. */
    FOREIGN_INFLUENCER,
    /** 뷰티 콘텐츠 중심이 아닌 계정. */
    NOT_BEAUTY;

    public boolean beauty() {
        return this == INFLUENCER || this == COMPANY;
    }

    public boolean company() {
        return this == COMPANY;
    }
}

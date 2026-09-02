/**
 * 인스타그램 수집 어댑터 계층만 — IG/HikerAPI HTTP 수집·DTO 정규화·에러 매핑. DB 쓰기·Spring 의존·
 * 소비자별 저장 로직은 여기 반입 금지, 소비 모듈(monitoring 등) 소관이다(ARCHITECTURE.md §4-4,
 * 스펙 2026-08-31-instagram-hiker-selfcrawl-hybrid-design.md). contract-analysis·common-llm과
 * 동류의 순수 JDK 공유 예외 — 두 백엔드(자체크롤·Hiker)를 한 인터페이스 뒤에 두고 폴백을
 * 모듈 안에 가둔다.
 */
package com.celfit.instagram.source;

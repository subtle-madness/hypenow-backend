package com.celfit.crawler.content.domain;

/**
 * content가 어떤 경로로 생겼는지 — 수집 대상 여부를 가른다.
 *
 * <ul>
 *   <li>{@link #DISCOVERY} — 해시태그 발굴의 부산물. raw만 보관하고 상세·댓글 수집 대상이 아니다.
 *       범위 안이면 QUALIFIED 인플루언서의 6개월 열거가 같은 shortCode로 다시 잡으므로 유실 없음.</li>
 *   <li>{@link #ENUMERATION} — QUALIFIED 인플루언서의 6개월 열거(CollectJob)가 만든 게시물.
 *       진짜 수집 대상 — 상세·댓글 수집 파이프라인이 이 origin만 대상으로 삼는다.</li>
 * </ul>
 */
public enum ContentOrigin { DISCOVERY, ENUMERATION }

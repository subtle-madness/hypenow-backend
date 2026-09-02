package com.celfit.instagram.source;

import java.util.List;

/** 태그 열거 1페이지 — posts는 응답 순서 그대로(태그된 시점 순 — 중단 판정은 호출자가 페이지 단위로 한다). */
public record TaggedPage(List<PostInfo> posts, String nextPageId) {}

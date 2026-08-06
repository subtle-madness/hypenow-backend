package com.celfit.monitoring.hiker;

/**
 * 게시자(인플루언서) 프로필 — /v2/user/by/id 파싱 결과(브랜드 태그 모니터링 스펙 §2).
 * 브랜드 계정 프로필(ProfileInfo)과 달리 비공개(isPrivate)가 오류가 아니라 관측값이다 —
 * 게시자 캐시(author_profile)는 표시·집계용이지 수집 가능성 판정용이 아니다.
 * 원형은 전송 계층(RecordingHikerHttp)이 남기므로 rawJson을 나르지 않는다.
 */
public record AuthorInfo(String igUserId, String username, String fullName, Long followers,
		Long following, Long mediaCount, String biography, String profilePicUrl, boolean isPrivate) {}

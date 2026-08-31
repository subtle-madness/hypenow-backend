package com.celfit.instagram.source;

/**
 * 게시자(인플루언서) 프로필 — /v2/user/by/id 파싱 결과(브랜드 태그 모니터링 스펙 §2).
 * 브랜드 계정 프로필(ProfileInfo)과 달리 비공개(isPrivate)가 오류가 아니라 관측값이다 —
 * 게시자 캐시(author_profile)는 표시·집계용이지 수집 가능성 판정용이 아니다.
 * 원형은 전송 계층(RecordingHikerHttp)이 남기므로 rawJson을 나르지 않는다.
 *
 * <p>isVerified는 브랜드 was 계약 필드(2026-08-07 스펙 §3-2)다 — isPrivate과 달리 Boolean인 건
 * 키 부재(null)와 관측된 미인증(false)을 구분해야 하기 때문(비공개는 키가 없으면 공개로 봐도
 * 무방하지만, 인증뱃지는 "확인 못 함"을 미인증으로 단정하면 화면이 거짓을 표시한다).
 */
public record AuthorInfo(String igUserId, String username, String fullName, Long followers,
		Long following, Long mediaCount, String biography, String profilePicUrl, boolean isPrivate,
		Boolean isVerified) {}

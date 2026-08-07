package com.celfit.monitoring.hiker;

/**
 * 프로필 스냅샷 원재료 — rawJson은 응답 원문(스냅샷 감사·추후 재파싱용).
 * fullName·profilePicUrl은 profile_meta 저장용(계약 §3, v1.1) — 취득 불가 시 null.
 * biography는 브랜드 태그 모니터링의 등록 시 1회 관측값(brand_account.biography, 스펙 §2) — 취득 불가 시 null.
 *
 * <p>isVerified·externalUrl은 브랜드 was 계약 필드(2026-08-07 스펙 §3-2 — 같은 프로필 응답에서
 * 추가 콜 0으로 뽑는다). isVerified가 Boolean인 건 <b>키 부재(null)와 관측된 미인증(false)</b>을
 * 구분하기 위해서다 — 화면이 "뱃지 없음"과 "확인 못 함"을 다르게 표시한다.
 */
public record ProfileInfo(String username, String userId, Long followers, Long following,
		Long mediaCount, String fullName, String profilePicUrl, String biography,
		Boolean isVerified, String externalUrl, String rawJson) {}

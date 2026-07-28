package com.celfit.was.v1.brand;

/**
 * 브랜드 협업 인플루언서 1행 — 리포트 브랜드 칩 호버용 (분석 결과끼리 조인, §4-4 허용).
 *
 * @param influencerId    계정 핸들(= influencerId, 설계 확정)
 * @param name             표시명(accounts.display_name)
 * @param profileImageUrl  프로필 이미지 — 아카이브 있으면 /img/ 상대경로, 없으면 원본 URL 폴백
 * @param followers        팔로워 수
 * @param collabCount       해당 브랜드와의 협업(sponsored) 게시물 수
 * @param lastCollabAt      최근 협업일 — KST 달력 날짜 YYYY-MM-DD 문자열
 */
public record BrandInfluencer(String influencerId, String name, String profileImageUrl,
		Long followers, Long collabCount, String lastCollabAt) {
}

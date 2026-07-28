package com.celfit.was.v1.influencer;

/** 유사 인플루언서 1행 — traits·카테고리 겹침 휴리스틱(07-27 확정, 임베딩 아님).
 *  matchPct: traits Jaccard(교집합/합집합)×100 반올림, 교집합 0이면 null(카테고리만 같은 후보).
 *  overlap·union 모두 중복 제거(DISTINCT) — matchPct ≤ 100 보장. */
public record SimilarInfluencer(String influencerId, String name, String profileImageUrl,
		String tagline, Integer matchPct) {
}

package com.celfit.was.v1.org;

/** POST /v1/org/members 요청 본문 — email 정확 일치로 기존 가입 계정을 찾는다(설계 §조직 셀프서비스). */
public record OrgMemberAddRequest(String email, String orgRole) {
}

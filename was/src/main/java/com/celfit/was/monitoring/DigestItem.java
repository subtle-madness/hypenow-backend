package com.celfit.was.monitoring;

/**
 * app.monitoring_digests.items[] 원소의 저장 형태(2026-08-27 주간 개편 §3). 기존 4필드
 * (category·type·summary·count)에 섹션 합산 지표 {@code metrics}를 더한 확장이다 - 없는 항목은
 * null로 내려 프론트가 키 부재와 값 없음을 구분하지 않아도 되게 한다(계약 무결성 규칙 #1).
 *
 * <p>응답 DTO({@code DigestResponse.Item})와 형태가 같지만 계층이 다르다: 이 record는 잡이
 * 쓰는 저장 모델이고, 응답 DTO는 v1 계약이다. 같은 jsonb를 양쪽이 각자 직렬화·역직렬화한다.
 */
public record DigestItem(String category, String type, String summary, int count, Metrics metrics) {

	/**
	 * 섹션 합산 지표. views는 <b>릴스만</b> 집계된다 - 피드 게시물의 조회수는 항상 NULL이라는
	 * 관측 규칙(CLAUDE.md 함정) 때문에, 그 주에 릴스가 하나도 없으면 views 자체가 null이 된다.
 	 */
	public record Metrics(Long views, Long likes, Long comments) {
	}
}

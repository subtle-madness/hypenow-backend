package com.celfit.monitoring.hiker;

/**
 * 단건 응답은 왔지만(200) 브랜드 direct 등록에 쓸 수 없는 셰이프 — 현재는 taken_at 미상 1종
 * (2026-08-18 direct 통합 §T2). {@code brand_tagged_post.taken_at}이 NOT NULL이라 그대로는 저장이
 * 불가하다.
 *
 * <p>{@link HikerFetchException}을 상속한다 — 재시도 여지가 있는 일반 실패(5xx·타임아웃)와 같은
 * catch(RuntimeException) 격리 경로를 그대로 타되(스윕·enrich의 격리 catch는 부모 타입으로 잡는다),
 * direct 등록 API 컨트롤러만 이 구체 타입으로 먼저 잡아 422 POST_UNSUPPORTED로 응답한다 — 부모
 * 타입 그대로였다면 전역 {@code ApiExceptionHandler}가 모든 HikerFetchException을 502 FETCH_FAILED로
 * 뭉개 "재시도해도 되는 일시 오류"와 "이 게시물은 영구히 등록 불가"를 구분하지 못한다.
 */
public class PostShapeUnsupportedException extends HikerFetchException {

	public PostShapeUnsupportedException(String message) {
		super(message);
	}
}

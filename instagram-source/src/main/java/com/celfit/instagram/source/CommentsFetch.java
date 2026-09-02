package com.celfit.instagram.source;

import java.util.List;

/**
 * 댓글 수집 결과 — complete=false면 중간 페이지 콜 실패로 뒤 페이지를 못 받은 부분 결과다.
 * 받은 페이지분은 그대로 저장 가능하지만, 브랜드 워터마크처럼 "이 게시물 댓글을 다 봤다"를
 * 전제하는 갱신은 하면 안 된다(다음 스윕이 재시도할 근거를 지운다).
 */
public record CommentsFetch(List<CommentInfo> comments, boolean complete) {}

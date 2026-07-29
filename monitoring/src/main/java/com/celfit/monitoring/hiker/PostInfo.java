package com.celfit.monitoring.hiker;

/**
 * 게시물 스냅샷 원재료 — 6지표(좋아요·댓글·조회·저장·공유·리포스트).
 * 취득 불가 지표는 null이다: 조회·저장·공유는 릴스 전용이고, 피드·캐러셀 응답에는 키 자체가 없다(findings §2).
 * takenAt은 taken_at(epoch seconds) — 핀 고정 게시물 때문에 배열 순서를 믿을 수 없어 재정렬 기준으로 쓴다.
 * rawJson은 이 게시물만이 아니라 **응답 body 전체**다(열거면 그 페이지의 12건 전부).
 * 그래서 감사용 원형 적재는 여기서 하지 않는다 — 전송 계층(RecordingHikerHttp)이 콜 단위로 남긴다.
 */
public record PostInfo(String shortCode, String username, String contentType, String caption,
		Long takenAt, Long likes, Long comments, Long views, Long saves,
		Long shares, Long reposts, String rawJson) {}

package com.celfit.monitoring.hiker;

/**
 * 게시물 스냅샷 원재료 — 6지표(좋아요·댓글·조회·저장·공유·리포스트).
 * 취득 불가 지표는 null이다: 조회·저장·공유는 릴스 전용이고, 피드·캐러셀 응답에는 키 자체가 없다(findings §2).
 * takenAt은 taken_at(epoch seconds) — 핀 고정 게시물 때문에 배열 순서를 믿을 수 없어 재정렬 기준으로 쓴다.
 * rawJson은 이 게시물만이 아니라 **응답 body 전체**다(열거면 그 페이지의 12건 전부).
 * 그래서 감사용 원형 적재는 여기서 하지 않는다 — 전송 계층(RecordingHikerHttp)이 콜 단위로 남긴다.
 *
 * <p>viewsTrusted는 "views가 null인 게 진짜 부재인가"를 하류에 알린다: 열거 경로의 조회수는
 * /v2/user/clips 보강으로만 채워지는데 그 보강은 실패해도 스윕을 계속한다(조용히 null).
 * 이 플래그가 없으면 보강 실패가 "조회수 비공개 전환"으로 오탐돼 알람이 나간다(스펙 §3-2).
 */
public record PostInfo(String shortCode, String username, String contentType, String caption,
		Long takenAt, Long likes, Long comments, Long views, Long saves,
		Long shares, Long reposts, String rawJson, boolean viewsTrusted) {}

package com.celfit.contract.analysis;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 서빙 콘텐츠 1행 (미러: analytics.v_contents → contents).
 * hypeScore: 0~100. 산식 정본은 analytics.hype_score()(analytics/views/02_serving.sql) —
 * clamp(타입별 앵커 매핑(Q), 0, 100) × 0.5^(경과일/halflife) (경과일은 미러 갱신 시점 기준).
 * Java에는 계산 로직이 없다(미러 값 통과).
 * 릴스인데 조회수 NULL이면 NULL (정렬은 NULLS LAST).
 * metricCapturedAt: 지표 고정(+3일) 스냅샷의 수집 시각 (스펙 updatedAt 재료).
 * adMarked: 인스타 유료 파트너십 태그(릴스 전용, 피드 false) — LLM 분석 프롬프트에 싣는 확정 사실.
 *   광고 판정의 정본은 캡션 분류(content_analyses.ad_type)이고 이건 보조 증거다.
 * hypeScorePrecise(맨 끝 필드 — CREATE OR REPLACE VIEW가 중간 삽입을 지원하지 않아 뷰·DDL·record
 * 세 곳 모두 맨 끝 추가로 통일, 스펙 2026-07-30-hype-score-v3-decay-after-mapping-design.md §10):
 * hypeScore(정수, 값·의미 불변)에 콘텐츠 출력 매핑(analytics.hype_score_output, 타입 무관 단일
 * 매핑)까지 적용한 소수값, 반올림 없이 소수 4자리로 자른 것 — 정수 반올림이 랭킹 경로 상위
 * 54건을 4개 정수값으로 압축해 표시·정렬이 short_code 알파벳순에 지배되던 동점 문제를 없앤다.
 * was는 이제 표시·정렬 모두 이 필드를 쓴다.
 */
public record Content(String shortCode, String accountHandle, String thumbnailUrl, String caption,
		OffsetDateTime postedAt, String contentType, BigDecimal videoDuration, String originalUrl,
		Long views, Long likes, Long comments, Long hypeScore, OffsetDateTime metricCapturedAt,
		Boolean adMarked, BigDecimal hypeScorePrecise) {
}

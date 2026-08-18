/**
 * LLM 전송 계층만 — Vertex AI HTTP 호출·SA 토큰 발급·재시도/백오프·에러 매핑. 프롬프트·판정
 * 로직·도메인 스키마(responseSchema 등)는 여기 반입 금지, 소비 모듈(analytics·monitoring)
 * 소관이다(ARCHITECTURE.md §4, 2026-08-18 신설 결정 — DECISIONS.md 참조).
 */
package com.celfit.common.llm;

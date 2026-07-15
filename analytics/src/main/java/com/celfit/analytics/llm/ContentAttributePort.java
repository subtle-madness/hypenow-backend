package com.celfit.analytics.llm;

/** 콘텐츠 속성 분석 포트 — 캡션 주, 썸네일 보조 (2026-07-14 캡션 분류 스펙). */
public interface ContentAttributePort {

	/** @param thumbnailUrl null이면 캡션만으로 분석한다 (썸네일 만료/게이트 off). */
	ContentAttributes analyze(String caption, String thumbnailUrl);
}

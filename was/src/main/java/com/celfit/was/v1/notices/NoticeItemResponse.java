package com.celfit.was.v1.notices;

import com.celfit.was.notices.NoticeRepository;

/**
 * 업데이트 소식 항목 응답. link는 링크가 없는 항목에서도 계약 무결성 규칙 #1(1.8)에 따라 키를
 * 생략하지 않고 명시적 {@code null}로 직렬화한다(record 기본 동작 — NON_NULL 미적용).
 */
public record NoticeItemResponse(String id, String tag, String summary, String body, NoticeLink link) {

	static NoticeItemResponse from(NoticeRepository.ItemRow row) {
		NoticeLink link = row.linkHref() == null ? null : new NoticeLink(row.linkHref(), row.linkLabel());
		return new NoticeItemResponse(String.valueOf(row.id()), row.tag(), row.summary(), row.body(), link);
	}
}

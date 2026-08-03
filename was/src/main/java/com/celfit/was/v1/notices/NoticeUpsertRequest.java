package com.celfit.was.v1.notices;

import java.util.List;

/** POST·PATCH /v1/admin/notices 공용 요청 본문 — 검증·파싱은 {@link NoticeAdminService}가 전담한다. */
public record NoticeUpsertRequest(String title, String publishedAt, List<Item> items) {

	public record Item(String tag, String summary, String body, LinkInput link) {
	}

	public record LinkInput(String href, String label) {
	}
}

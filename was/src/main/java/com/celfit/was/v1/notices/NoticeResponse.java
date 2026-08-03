package com.celfit.was.v1.notices;

import com.celfit.was.notices.NoticeRepository;
import com.celfit.was.v1.common.KstTimestamps;
import java.util.List;

/**
 * 업데이트 소식 응답 — 유저·어드민 목록 공용. date 컬럼은 DB에 없다 — published_at의 KST 달력 날짜를
 * 응답 시점에 파생한다({@link KstTimestamps#toKstDate}).
 */
public record NoticeResponse(String id, String title, String date, String publishedAt,
		List<NoticeItemResponse> items) {

	public static NoticeResponse from(NoticeRepository.NoticeRow row) {
		return new NoticeResponse(String.valueOf(row.id()), row.title(),
				KstTimestamps.toKstDate(row.publishedAt()).toString(), KstTimestamps.toKstIso(row.publishedAt()),
				row.items().stream().map(NoticeItemResponse::from).toList());
	}
}

package com.celfit.was.v1.monitoring;

import com.celfit.was.monitoring.DigestRow;
import com.celfit.was.v1.common.KstTimestamps;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 알림 다이제스트 응답(스펙 6.32). items는 저장 시점 jsonb를 그대로 파싱해 전달한다(서버 재가공
 * 없음 — 값은 다이제스트 생성 크론(후속 태스크)이 이미 확정해 저장한 것).
 *
 * <p>readAt은 계약 무결성 규칙 #1(1.8)이 짚은 그 사례다 — 키를 생략하면 프론트가 전 알림을
 * 읽음으로 오판해 안읽음 배지가 영구히 0이 된다. record 기본 동작(NON_NULL 미적용)으로 키를
 * 항상 유지한다.
 */
public record DigestResponse(String id, String date, String createdAt, String readAt, List<Item> items) {

	private static final TypeReference<List<Item>> ITEM_LIST = new TypeReference<>() {
	};

	public record Item(String category, String type, String summary, int count) {
	}

	/** itemsJson 파싱에 ObjectMapper가 필요해 from(DigestRow)이 아니라 이 시그니처로 둔다(기존 jsonb 읽기 관용구, PostDetailAssembler 참조). */
	public static DigestResponse from(DigestRow row, ObjectMapper objectMapper) {
		List<Item> items = objectMapper.readValue(row.itemsJson(), ITEM_LIST);
		return new DigestResponse(
				String.valueOf(row.id()),
				row.digestDate().toString(),
				KstTimestamps.toKstIso(row.createdAt()),
				KstTimestamps.toKstIso(row.readAt()),
				items);
	}
}

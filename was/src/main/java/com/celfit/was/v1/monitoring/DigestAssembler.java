package com.celfit.was.v1.monitoring;

import com.celfit.was.monitoring.DigestRow;
import com.celfit.was.v1.common.KstTimestamps;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * DigestRow → DigestResponse(6.32) 조립 — items jsonb 파싱만 한다(§4-2 표현 조립, PostDetailAssembler·
 * V1ContentReportAssembler와 같은 위치: DTO record는 순수, jsonb 읽기는 @Component Assembler 소유).
 */
@Component
public class DigestAssembler {

	private static final TypeReference<List<DigestResponse.Item>> ITEM_LIST = new TypeReference<>() {
	};

	private final ObjectMapper objectMapper;

	public DigestAssembler(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public DigestResponse toResponse(DigestRow row) {
		List<DigestResponse.Item> items = objectMapper.readValue(row.itemsJson(), ITEM_LIST);
		return new DigestResponse(
				String.valueOf(row.id()),
				row.digestDate().toString(),
				KstTimestamps.toKstIso(row.createdAt()),
				KstTimestamps.toKstIso(row.readAt()),
				items);
	}
}

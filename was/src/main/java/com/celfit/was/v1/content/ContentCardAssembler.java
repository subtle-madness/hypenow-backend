package com.celfit.was.v1.content;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** ContentCardRow → ContentCard(스펙 5.3) 순수 변환 — SELECT 절은 ContentCardRow.SELECT 참조. */
@Component
public class ContentCardAssembler {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter ISO_Z = DateTimeFormatter.ISO_INSTANT;
	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
	};
	private static final TypeReference<List<Map<String, Object>>> OBJ_LIST = new TypeReference<>() {
	};

	private final ObjectMapper objectMapper;

	public ContentCardAssembler(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/** 개인화 필드 없는 카드 — 비로그인 응답 경로가 쓴다(isContentsSaved=null → 직렬화 생략). */
	public ContentCard toCard(ContentCardRow r) {
		return toCard(r, null);
	}

	/**
	 * saved 마킹을 실은 카드 — 로그인 응답 경로가 쓴다(스펙 2절 Optional 규약).
	 * saved=null이면 필드 부재(비로그인), true/false면 저장 여부.
	 */
	public ContentCard toCard(ContentCardRow r, Boolean saved) {
		return new ContentCard(
				r.shortCode(), r.thumbnailUrl(), r.caption(),
				r.postedAt() == null ? null : r.postedAt().atZoneSameInstant(KST).toLocalDate().toString(),
				r.contentType(), r.mainCategory(), strings(r.subCategoriesJson()), r.adType(),
				r.videoDuration(), r.originalUrl(),
				r.metricCapturedAt() == null ? null : ISO_Z.format(r.metricCapturedAt().toInstant()),
				r.hypeScore(), r.views(), r.likes(), r.comments(),
				names(r.brandsJson()), names(r.productsJson()), strings(r.distributorsJson()),
				new ContentCard.Influencer(r.handle(), r.handle(), r.displayName(),
						r.profileImageUrl(), r.followers()),
				saved);
	}

	/** jsonb 문자열 배열 → List. null(미분석)은 빈 배열 — 스펙 카드에 null 배열은 없다. */
	private List<String> strings(String json) {
		if (json == null) {
			return List.of();
		}
		return objectMapper.readValue(json, STRING_LIST);
	}

	/** [{name,...}] 형태 jsonb → name 배열 평탄화 (스펙 5.3: 이름 문자열 배열). */
	private List<String> names(String json) {
		if (json == null) {
			return List.of();
		}
		return objectMapper.readValue(json, OBJ_LIST).stream()
				.map(o -> (String) o.get("name")).filter(Objects::nonNull).toList();
	}
}

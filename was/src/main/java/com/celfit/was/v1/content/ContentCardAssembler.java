package com.celfit.was.v1.content;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 카드 SELECT 절 공통 상수 — 목록(6.1)·recentContents(6.4)가 같은 행 형태를 쓴다.
 * ContentCardRow → ContentCard(스펙 5.3) 조립.
 */
@Component
public class ContentCardAssembler {

	static final String CARD_SELECT = """
			SELECT c.short_code, c.thumbnail_url, c.caption, c.posted_at, c.content_type,
			       c.video_duration, c.original_url, c.views, c.likes, c.comments,
			       c.hype_score, c.metric_captured_at,
			       an.main_category, an.sub_categories::text AS sub_categories_json, an.ad_type,
			       an.detected_brands::text AS brands_json,
			       an.detected_products::text AS products_json,
			       an.detected_distributors::text AS distributors_json,
			       a.handle, a.display_name, a.profile_image_url, a.followers
			""";

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

	public ContentCard toCard(ContentCardRow r) {
		return new ContentCard(
				r.shortCode(), r.thumbnailUrl(), r.caption(),
				r.postedAt() == null ? null : r.postedAt().atZoneSameInstant(KST).toLocalDate().toString(),
				r.contentType(), r.mainCategory(), strings(r.subCategoriesJson()), r.adType(),
				r.videoDuration(), r.originalUrl(),
				r.metricCapturedAt() == null ? null : ISO_Z.format(r.metricCapturedAt().toInstant()),
				r.hypeScore(), r.views(), r.likes(), r.comments(),
				names(r.brandsJson()), names(r.productsJson()), strings(r.distributorsJson()),
				new ContentCard.Influencer(r.handle(), r.handle(), r.displayName(),
						r.profileImageUrl(), r.followers()));
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

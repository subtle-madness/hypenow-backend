package com.celfit.was.v1.saved;

import com.celfit.was.v1.content.ContentCard;
import com.celfit.was.v1.saved.V1SavedRepository.SavedContentRow;
import com.celfit.was.v1.saved.V1SavedRepository.SavedInfluencerRow;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 저장 목록 조합기(스펙 6.6~6.11) — app 저장 행 + analysis 미러 조회 결과를 Java에서 결합한다(§4-4:
 * app·analysis SQL 조인 금지). 콘텐츠·인플루언서의 "미러 부재" 정책이 서로 다르다(각 메서드 주석 참조).
 */
@Component
public class V1SavedAssembler {

	private static final DateTimeFormatter ISO_Z = DateTimeFormatter.ISO_INSTANT;

	/**
	 * memo 정규화(스펙 6.7): null·빈 문자열·공백뿐이면 null, 그 외는 앞뒤 공백을 다듬어 보존한다.
	 * 저장 직전에 호출해 DB에도 정규화된 값이 들어가게 한다(RETURNING이 곧 정규화 결과).
	 */
	public static String normalizeMemo(String memo) {
		if (memo == null) {
			return null;
		}
		String trimmed = memo.strip();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/** OffsetDateTime → ISO 8601 UTC(Z) — savedAt 표기(스펙 3.4). */
	public String isoZ(OffsetDateTime at) {
		return at == null ? null : ISO_Z.format(at.toInstant());
	}

	public ContentItem toContentItem(ContentCard card, String memo, OffsetDateTime savedAt) {
		return new ContentItem(card, memo, isoZ(savedAt));
	}

	public InfluencerItem toInfluencerItem(ContentCard.Influencer influencer, String memo, OffsetDateTime savedAt) {
		return new InfluencerItem(influencer, memo, isoZ(savedAt));
	}

	/**
	 * 저장 순서(최근순)를 유지하며 카드와 결합한다. <b>미러(카드)에 없는 콘텐츠는 항목에서 제외</b>한다
	 * (스펙 6.6 [제안] 수용) — 카드 필드가 분석 산출이라 미분석 콘텐츠는 노출할 화면 요소가 없다.
	 */
	public List<ContentItem> toContentItems(List<SavedContentRow> saved, Map<String, ContentCard> cardsByCode) {
		return saved.stream()
				.filter(r -> cardsByCode.containsKey(r.shortCode()))
				.map(r -> toContentItem(cardsByCode.get(r.shortCode()), r.memo(), r.createdAt()))
				.toList();
	}

	/**
	 * 저장 순서(최근순)를 유지하며 프로필과 결합한다. <b>미러(accounts)에 없어도 항목을 남기고</b> handle만
	 * 채운 뒤 나머지는 null로 둔다 — 콘텐츠와 다른 정책인 이유: 인플루언서 항목은 식별자(handle)만으로도
	 * 의미가 있고 저장이 정본, 프로필 미러는 보조라서다(스펙 6.9~6.11).
	 */
	public List<InfluencerItem> toInfluencerItems(List<SavedInfluencerRow> saved,
			Map<String, ContentCard.Influencer> profilesByHandle) {
		return saved.stream()
				.map(r -> toInfluencerItem(
						profilesByHandle.getOrDefault(r.handle(),
								new ContentCard.Influencer(r.handle(), r.handle(), null, null, null)),
						r.memo(), r.createdAt()))
				.toList();
	}

	/** 저장 콘텐츠 목록 항목(6.6)·저장 응답(6.7) 공통 형태. */
	public record ContentItem(ContentCard content, String memo, String savedAt) {
	}

	/** 저장 인플루언서 목록 항목(6.9)·저장 응답(6.10) 공통 형태. */
	public record InfluencerItem(ContentCard.Influencer influencer, String memo, String savedAt) {
	}
}

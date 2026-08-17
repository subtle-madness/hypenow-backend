package com.celfit.monitoring.store;

import com.celfit.monitoring.ad.AdVerdictResult;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * brand_post_meta 접점 — 게시물 전역 최신 1행 upsert({@link PostMetaRepository} 동형,
 * 전면 전용 스키마 결정 08-06). 썸네일 CDN 서명(~4일 만료)은 매일 스윕 upsert가 자동 방어.
 */
@Repository
public class BrandPostMetaRepository {

	private static final Logger log = LoggerFactory.getLogger(BrandPostMetaRepository.class);

	private final JdbcTemplate db;
	private final ObjectMapper om = new ObjectMapper();

	public BrandPostMetaRepository(JdbcTemplate db) {
		this.db = db;
	}

	/**
	 * thumbnailUrl이 null이면(일시적 미취득) 기존 값을 덮지 않는다 — COALESCE(EXCLUDED, 기존값) 패턴.
	 * caption은 항상 EXCLUDED로 덮는다(수정 반영). first_seen_at은 갱신 안 함(최초 관측 보존).
	 * 무효 스킴 URL은 저장 전 null로 강등(PostMetaRepository·트랙 KK 동형).
	 *
	 * <p>영상·협찬 3필드(was 계약 §3-2)는 <b>컬럼마다 규칙이 다르다</b>:
	 * <ul>
	 * <li>video_url — COALESCE 보존. 썸네일과 같은 CDN 서명 URL(같은 media 노드·만료 갱신 대상)이라
	 *     null이 "영상 없음"이 아니라 "이 콜이 꽝"일 수 있다(세션 복권 실측 08-04: 같은 엔드포인트가
	 *     키를 실었다 뺐다 한다). 지우면 다음 스윕까지 하루 종일 영상이 안 나온다.</li>
	 * <li>video_duration — COALESCE 보존. video_url과 한 몸(같은 노드에서 함께 실리고 함께 빠진다).</li>
	 * <li>is_paid_partnership — EXCLUDED로 덮는다. 여기서 null은 취득 실패가 아니라 <b>판정
	 *     unknown</b>이 계약이고(PostInfo 주석), 보존하면 협찬 해제를 영영 못 따라간다.</li>
	 * </ul>
	 */
	public void upsert(String shortCode, String username, String contentType, LocalDate uploadedAt,
			String caption, String thumbnailUrl, String videoUrl, Double videoDuration,
			Boolean isPaidPartnership) {
		String normalizedThumbnailUrl = normalizeThumbnailUrl(shortCode, thumbnailUrl);
		db.update("""
				INSERT INTO brand_post_meta (short_code, username, content_type, uploaded_at, caption, thumbnail_url,
				                             video_url, video_duration, is_paid_partnership, first_seen_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
				ON CONFLICT (short_code) DO UPDATE SET
				  username = EXCLUDED.username,
				  content_type = EXCLUDED.content_type,
				  uploaded_at = EXCLUDED.uploaded_at,
				  caption = EXCLUDED.caption,
				  thumbnail_url = COALESCE(EXCLUDED.thumbnail_url, brand_post_meta.thumbnail_url),
				  video_url = COALESCE(EXCLUDED.video_url, brand_post_meta.video_url),
				  video_duration = COALESCE(EXCLUDED.video_duration, brand_post_meta.video_duration),
				  is_paid_partnership = EXCLUDED.is_paid_partnership""",
				shortCode, username, contentType, uploadedAt, caption, normalizedThumbnailUrl,
				videoUrl, videoDuration, isPaidPartnership);
	}

	private static String normalizeThumbnailUrl(String shortCode, String thumbnailUrl) {
		if (thumbnailUrl == null || thumbnailUrl.isBlank()) {
			return null;
		}
		String lower = thumbnailUrl.toLowerCase(Locale.ROOT);
		if (lower.startsWith("http://") || lower.startsWith("https://")) {
			return thumbnailUrl;
		}
		String excerpt = thumbnailUrl.length() > 100 ? thumbnailUrl.substring(0, 100) : thumbnailUrl;
		log.warn("무효 스킴 thumbnail_url 폐기: shortCode={}, value={}", shortCode, excerpt);
		return null;
	}

	/** 광고 표기 판정 상태 — ad_verdict NULL 또는 judged_caption_hash 불일치가 재판정 대상(스펙 §7). */
	public record AdJudgmentState(String adVerdict, String judgedCaptionHash) {
	}

	public Map<String, AdJudgmentState> findAdJudgmentState(Collection<String> shortCodes) {
		if (shortCodes.isEmpty()) {
			return Map.of();
		}
		String placeholders = String.join(",", Collections.nCopies(shortCodes.size(), "?"));
		Map<String, AdJudgmentState> out = new HashMap<>();
		db.query("SELECT short_code, ad_verdict, judged_caption_hash FROM brand_post_meta WHERE short_code IN ("
						+ placeholders + ")",
				rs -> {
					out.put(rs.getString("short_code"),
							new AdJudgmentState(rs.getString("ad_verdict"), rs.getString("judged_caption_hash")));
				}, shortCodes.toArray());
		return out;
	}

	/**
	 * 판정 결과 기록 — violations·evidence는 애플리케이션에서 jsonb로 직렬화한다(AlarmEventRepository
	 * {@code ?::jsonb} 관용구). captionHash는 호출부가 계산한 판정 시점 caption의 MD5(스펙 §4).
	 */
	public void updateAdVerdict(String shortCode, AdVerdictResult result, String captionHash, Instant judgedAt) {
		db.update("""
				UPDATE brand_post_meta
				SET ad_verdict = ?, ad_verdict_source = ?, ad_violations = ?::jsonb, ad_evidence = ?::jsonb,
				    ad_judged_at = ?, judged_caption_hash = ?
				WHERE short_code = ?""",
				result.verdict(), result.source(), om.writeValueAsString(result.violations()),
				om.writeValueAsString(result.evidence()), Timestamp.from(judgedAt), captionHash, shortCode);
	}
}

package com.celfit.monitoring.store;

import java.time.LocalDate;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * post_meta 테이블 접점 — 게시물 단위 최신 1행 upsert(계약 §3 post_meta).
 * profile_meta와 같은 관용구: 스냅샷과 달리 이력 없이 최신 값만 유지한다.
 */
@Repository
public class PostMetaRepository {

	private static final Logger log = LoggerFactory.getLogger(PostMetaRepository.class);

	private final JdbcTemplate db;

	public PostMetaRepository(JdbcTemplate db) {
		this.db = db;
	}

	/**
	 * thumbnailUrl이 null이면(일시적 미취득) 기존 값을 덮지 않는다 — COALESCE(EXCLUDED, 기존값) 패턴.
	 * first_seen_at은 갱신 안 함(최초 관측 보존).
	 *
	 * <p><b>캡션 3-상태 계약(트랙 HH, 데이터 보호 결함 수정)</b> — null=미수집(파싱 실패) /
	 * ''=확인된 무캡션 / 값=원문. caption이 null이면(수집 실패) 기존 캡션을 지우지 않고 보존한다
	 * (thumbnailUrl과 동일 COALESCE 패턴). 과거엔 caption을 항상 EXCLUDED로 덮었는데, 호출부
	 * (SnapshotWriter)가 null을 ""로 강제 변환해 넘기던 시절엔 이게 곧 "파싱 실패가 기존 캡션을
	 * 빈 문자열로 지우는" 결손 경로였다. 빈 문자열("")은 "확인된 무캡션"이라는 정당한 값이므로
	 * COALESCE에 걸리지 않고 정상적으로 덮는다 — null만 "미확보"로 보호 대상이다.
	 *
	 * <p>트랙 KK(profile_meta 결함 ②)와 동형 — thumbnailUrl도 저장 전 스킴을 정규화한다. Hiker
	 * 업스트림이 이따금 무효 스킴을 줄 수 있는 구조가 profile_pic_url과 동일하므로, http(s)가 아니면
	 * null로 강등해 COALESCE 보존 시맨틱에 자연히 태운다(무효값이 오면 기존 유효 썸네일을 지우지 않음).
	 */
	public void upsert(String shortCode, String username, String contentType, LocalDate uploadedAt,
			String caption, String thumbnailUrl) {
		String normalizedThumbnailUrl = normalizeThumbnailUrl(shortCode, thumbnailUrl);
		db.update("""
				INSERT INTO post_meta (short_code, username, content_type, uploaded_at, caption, thumbnail_url, first_seen_at)
				VALUES (?, ?, ?, ?, ?, ?, now())
				ON CONFLICT (short_code) DO UPDATE SET
				  username = EXCLUDED.username,
				  content_type = EXCLUDED.content_type,
				  uploaded_at = EXCLUDED.uploaded_at,
				  caption = COALESCE(EXCLUDED.caption, post_meta.caption),
				  thumbnail_url = COALESCE(EXCLUDED.thumbnail_url, post_meta.thumbnail_url)""",
				shortCode, username, contentType, uploadedAt, caption, normalizedThumbnailUrl);
	}

	/**
	 * ProfileMetaRepository.normalizeImageUrl과 동일 규칙(트랙 KK) — http(s)가 아니면 null로 강등해
	 * 저장·서빙에 무효 URL이 흘러가지 않게 한다.
	 */
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
}

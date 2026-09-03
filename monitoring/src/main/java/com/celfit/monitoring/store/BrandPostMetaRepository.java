package com.celfit.monitoring.store;

import com.celfit.monitoring.ad.AdVerdictResult;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
	 * first_seen_at은 갱신 안 함(최초 관측 보존). 무효 스킴 URL은 저장 전 null로 강등
	 * (PostMetaRepository·트랙 KK 동형).
	 *
	 * <p><b>캡션 3-상태 계약(트랙 HH, PostMetaRepository 수정과 동형 결함 수정)</b> — null=미수집
	 * (파싱 실패) / ""=확인된 무캡션 / 값=원문. caption이 null이면(수집 실패) 기존 캡션을 지우지
	 * 않고 보존한다(thumbnailUrl과 동일 COALESCE 패턴). 과거엔 caption을 항상 EXCLUDED로 덮었는데,
	 * 호출부(BrandSnapshotWriter)가 null을 ""로 강제 변환해 넘기던 시절엔 이게 곧 "파싱 실패가
	 * 기존 캡션을 빈 문자열로 지우는" 결손 경로였다. 빈 문자열("")은 "확인된 무캡션"이라는 정당한
	 * 값이므로 COALESCE에 걸리지 않고 정상적으로 덮는다.
	 *
	 * <p>영상·협찬 3필드(was 계약 §3-2)는 <b>컬럼마다 규칙이 다르다</b>:
	 * <ul>
	 * <li>video_url — COALESCE 보존. 썸네일과 같은 CDN 서명 URL(같은 media 노드·만료 갱신 대상)이라
	 *     null이 "영상 없음"이 아니라 "이 콜이 꽝"일 수 있다(세션 복권 실측 08-04: 같은 엔드포인트가
	 *     키를 실었다 뺐다 한다). 지우면 다음 스윕까지 하루 종일 영상이 안 나온다.</li>
	 * <li>video_duration — COALESCE 보존. video_url과 한 몸(같은 노드에서 함께 실리고 함께 빠진다).</li>
	 * <li>is_paid_partnership — COALESCE 보존(S3, 2026-09-03 배포 전 감사 수정). null은 대개 <b>판정
	 *     unknown</b>이 계약이지만(PostInfo 주석), self(embed)는 이 필드를 취득할 수단이 아예 없어
	 *     구조적으로 항상 null을 넘긴다 — Hiker가 먼저 관측한 진짜 unknown이 아니라 self의 "취득
	 *     불가"다. EXCLUDED로 무조건 덮으면 광고 판정 Tier0 최우선 신호(AdDisclosureJudgeService)로
	 *     이미 확정된 협찬 판정이 self 재수집에 지워진다. thumbnail_url·video_url과 같은 COALESCE
	 *     보호로 통일한다.</li>
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
				  caption = COALESCE(EXCLUDED.caption, brand_post_meta.caption),
				  thumbnail_url = COALESCE(EXCLUDED.thumbnail_url, brand_post_meta.thumbnail_url),
				  video_url = COALESCE(EXCLUDED.video_url, brand_post_meta.video_url),
				  video_duration = COALESCE(EXCLUDED.video_duration, brand_post_meta.video_duration),
				  is_paid_partnership = COALESCE(EXCLUDED.is_paid_partnership, brand_post_meta.is_paid_partnership)""",
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
	 * 미판정 잔여 백필(스펙 §7 개정, 2026-08-18) 대상 — 판정에 필요한 컬럼만 담는다. 브랜드
	 * 스코프가 없다: 스윕 재열거 창(180일)을 벗어나 정기 스윕이 다시 만나지 못하는 게시물도
	 * 캡션은 이미 이 테이블에 있으므로 Hiker 재조회 없이 판정 가능하다({@link
	 * com.celfit.monitoring.ad.AdDisclosureJudgeService#backfillUnjudged}).
	 */
	public record UnjudgedPost(String shortCode, String caption, String contentType, String videoUrl,
			Boolean isPaidPartnership) {
	}

	/**
	 * {@code idx_brand_post_meta_unjudged} 부분 인덱스가 바깥쪽 {@code ad_verdict IS NULL}을 커버한다 —
	 * 잔량이 줄수록 공짜에 수렴. has_own_link 필터(2026-08-19 경쟁사 판정 제거 설계 §3)는 EXISTS로 건다
	 * — 한 게시물이 여러 브랜드에 태그될 수 있어(brand_tagged_post N:1) 단순 JOIN이면 own 연결 브랜드가
	 * 하나라도 있는 게시물이 행 중복될 수 있다. 활성 own 연결이 하나도 없는 브랜드에만 태그된 게시물만
	 * 걸러진다 — 경쟁사·own 브랜드 둘 다에 태그된 게시물은 계속 판정 대상이다.
	 */
	public List<UnjudgedPost> findUnjudged(int limit) {
		return db.query("""
				SELECT short_code, caption, content_type, video_url, is_paid_partnership
				FROM brand_post_meta m
				WHERE ad_verdict IS NULL
				  AND EXISTS (
				    SELECT 1 FROM brand_tagged_post t
				    JOIN brand_account b ON b.id = t.brand_id
				    WHERE t.short_code = m.short_code AND b.has_own_link = true
				  )
				ORDER BY short_code
				LIMIT ?""",
				(rs, rowNum) -> new UnjudgedPost(rs.getString("short_code"), rs.getString("caption"),
						rs.getString("content_type"), rs.getString("video_url"),
						(Boolean) rs.getObject("is_paid_partnership")),
				limit);
	}

	/**
	 * 백필 완료 로그("잔여 N건 중 M건 처리")의 N — 이번 배치 상한(limit)과 무관한 전체 잔량.
	 * has_own_link 필터는 {@link #findUnjudged}와 동일(EXISTS 근거도 동일).
	 */
	public int countUnjudged() {
		Integer count = db.queryForObject("""
				SELECT count(*) FROM brand_post_meta m
				WHERE ad_verdict IS NULL
				  AND EXISTS (
				    SELECT 1 FROM brand_tagged_post t
				    JOIN brand_account b ON b.id = t.brand_id
				    WHERE t.short_code = m.short_code AND b.has_own_link = true
				  )""",
				Integer.class);
		return count == null ? 0 : count;
	}

	/**
	 * 판정 결과 기록 — violations·evidence는 이 메서드 안에서 jsonb로 직렬화한다({@code ?::jsonb}
	 * 캐스팅 문법 재사용). captionHash는 호출부가 계산한 판정 시점 caption의 MD5(스펙 §4).
	 *
	 * <p>대상 short_code가 이미 사라진 경우(0-row) 예외를 던지지 않는다 — 다음 스윕이 자연 재판정하므로
	 * 호출부 분기는 불필요하지만, LLM 비용을 들인 판정 결과가 소리 없이 유실되는 경로라 경고 로그는 남긴다.
	 */
	public void updateAdVerdict(String shortCode, AdVerdictResult result, String captionHash, Instant judgedAt) {
		int updated = db.update("""
				UPDATE brand_post_meta
				SET ad_verdict = ?, ad_verdict_source = ?, ad_violations = ?::jsonb, ad_evidence = ?::jsonb,
				    ad_judged_at = ?, judged_caption_hash = ?
				WHERE short_code = ?""",
				result.verdict(), result.source(), om.writeValueAsString(result.violations()),
				om.writeValueAsString(result.evidence()), Timestamp.from(judgedAt), captionHash, shortCode);
		if (updated == 0) {
			log.warn("광고 판정 기록 대상 없음 — short_code={} (메타 미존재, 판정 결과 유실)", shortCode);
		}
	}
}

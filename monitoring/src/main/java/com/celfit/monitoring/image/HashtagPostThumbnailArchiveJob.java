package com.celfit.monitoring.image;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 해시태그 발견 게시물 썸네일 아카이브({@link BrandPostThumbnailArchiveJob}과 동형) — 대상은
 * {@code brand_hashtag_post} 중 <b>verdict='RELEVANT'만</b>이다. 서빙 계약(was
 * BrandReadRepository.findHashtagPosts)이 RELEVANT만 노출한다.
 *
 * <p>2026-08-27 해시태그 직접 수집 전환으로 이 감지 구조 자체가 폐기됐다 — 이 잡은 구
 * {@code brand_hashtag_post} 잔존 행을 서빙하기 위해서만 의도적으로 얼려둔 죽은 경로이고,
 * 다음 릴리스에 테이블(brand_hashtag_post·brand_hashtag_post_matched_tags)과 함께 제거된다
 * (expand-contract, 설계 §1).
 *
 * <p>PK가 (brand_id, short_code)라 같은 게시물이 브랜드별 행으로 중복될 수 있다 — 후보는
 * {@code DISTINCT ON (short_code)}로 접고, UPDATE도 short_code 기준으로 쳐서 전 브랜드 행에
 * 같은 아카이브 경로를 채운다(같은 게시물 = 같은 썸네일).
 *
 * <p>배치 상한은 08-25 완전히 제거됐다 — 이력·근거는 {@link BrandPostThumbnailArchiveJob} 클래스
 * 주석 참고. 상한이 없으니 후보 전량이 매 스윕에서 처리된다.
 */
public class HashtagPostThumbnailArchiveJob {

	private static final Logger log = LoggerFactory.getLogger(HashtagPostThumbnailArchiveJob.class);

	static final String OBJECT_PREFIX = "monitor-hashtag-post/";
	// ProfileImageArchiveJob.CACHE_CONTROL과 동일한 값 — 동형 유지.
	static final String CACHE_CONTROL = "public, max-age=86400";

	private final JdbcTemplate db;
	private final ImageStore store;
	private final ImageDownloader downloader;
	private final String parUrl;

	public HashtagPostThumbnailArchiveJob(JdbcTemplate db, ImageStore store, ImageDownloader downloader,
			String parUrl) {
		this.db = db;
		this.store = store;
		this.downloader = downloader;
		this.parUrl = parUrl;
	}

	public void run() {
		if (parUrl == null || parUrl.isBlank()) {
			log.info("MONITORING_IMAGE_PAR_URL 미설정 — 해시태그 게시물 썸네일 아카이브 스킵(no-op)");
			return;
		}

		// DISTINCT ON: 같은 short_code가 여러 브랜드 행일 때 1건만 — first_seen_at DESC로 최신
		// 관측 URL(가장 늦게 발견된 행의 서명이 가장 늦게 만료)을 고른다.
		List<Candidate> candidates = db.query("""
				SELECT DISTINCT ON (short_code)
				       short_code, thumbnail_url, image_object_path, image_source_name
				FROM brand_hashtag_post
				WHERE verdict = 'RELEVANT' AND thumbnail_url LIKE 'http%'
				ORDER BY short_code, first_seen_at DESC
				""", (rs, i) -> new Candidate(rs.getString("short_code"), rs.getString("thumbnail_url"),
				rs.getString("image_object_path"), rs.getString("image_source_name")));

		// 만료 URL은 시도해도 영원히 403 — 걸러내고 남은 예산은 만료 임박 순으로(근거는 CdnExpiry 주석).
		// 주의: brand_hashtag_post는 insert-only(insertPost가 ON CONFLICT DO NOTHING, 재목격분은
		// existingCodes에서 걸러져 INSERT에 도달조차 안 함)라 thumbnail_url이 재조회로 갱신되지 않는다 —
		// 이 잡에서 "만료 제외"는 일시 상태가 아니라 사실상 영구 유실이다(첫 ~4일 창을 놓친 게시물).
		// 재목격 시 URL만 갱신하는 별도 경로는 저장 계약("DB에 있는 코드는 재판정하지 않는다")과 얽혀
		// 후속 트랙으로 분리(KK §CDN 만료 필터).
		long nowEpoch = Instant.now().getEpochSecond();

		int archived = 0;
		int skipped = 0;
		int failed = 0;
		int expired = 0;
		for (CdnExpiry.Ranked<Candidate> r : CdnExpiry.soonestExpiryFirst(candidates, Candidate::thumbnailUrl)) {
			Candidate c = r.item();
			String sourceName;
			try {
				sourceName = sourceName(c.thumbnailUrl());
			} catch (RuntimeException e) {
				// 원본 URL 파싱 실패 — 이 건만 스킵하고 다음 스윕에서 재시도(다른 게시물은 계속 처리).
				log.warn("해시태그 게시물 썸네일 URL 파싱 실패 — 스킵: shortCode={}", c.shortCode(), e);
				skipped++;
				continue;
			}
			if (c.imageObjectPath() != null && sourceName.equals(c.imageSourceName())) {
				skipped++;   // 파일명 미변경 — 재다운로드 불필요(상한 미소모).
				continue;
			}
			if (r.expired(nowEpoch)) {
				expired++;   // CDN 서명 만료 — 시도해도 403이라 스킵.
				continue;
			}
			try {
				ImageDownloader.Downloaded img = downloader.fetch(c.thumbnailUrl());
				String objectPath = OBJECT_PREFIX + c.shortCode() + ".jpg";
				store.put(objectPath, img.bytes(), img.contentType(), CACHE_CONTROL);
				// short_code 기준 — 같은 게시물의 전 브랜드 행에 같은 경로를 채운다.
				db.update("""
						UPDATE brand_hashtag_post
						   SET image_object_path = ?, image_source_name = ?, image_archived_at = now()
						 WHERE short_code = ?
						""", objectPath, sourceName, c.shortCode());
				archived++;
			} catch (Exception e) {
				// 건 단위 격리 — 한 게시물의 다운로드/업로드 실패가 나머지 게시물을 막지 않는다.
				failed++;
				log.warn("해시태그 게시물 썸네일 아카이브 실패 — shortCode={}", c.shortCode(), e);
			}
		}
		log.info("해시태그 게시물 썸네일 아카이브 완료 — 아카이브 {}건 / 스킵 {}건 / 실패 {}건 / 만료 제외 {}건",
				archived, skipped, failed, expired);
	}

	/**
	 * URL 경로의 마지막 세그먼트 — 쿼리스트링 제외({@link BrandPostThumbnailArchiveJob#sourceName}과
	 * 동일 관용구).
	 */
	static String sourceName(String url) {
		String path = URI.create(url).getPath();
		return path.substring(path.lastIndexOf('/') + 1);
	}

	private record Candidate(String shortCode, String thumbnailUrl, String imageObjectPath, String imageSourceName) {
	}
}

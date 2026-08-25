package com.celfit.monitoring.image;

import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 해시태그 발견 게시물 작성자 프로필 사진 아카이브({@link HashtagPostThumbnailArchiveJob}·{@link
 * AuthorProfileImageArchiveJob}과 동형, 2026-08-17) — brand_hashtag_post.author_profile_pic_url이
 * 인스타 서명 CDN 원본이라 며칠 뒤 만료되면 프론트 아바타가 깨진다. 대상은 {@link
 * HashtagPostThumbnailArchiveJob}과 같은 이유로 <b>verdict='RELEVANT'만</b>이다 — was 서빙 계약
 * (BrandReadRepository.findHashtagPosts)이 RELEVANT만 노출하므로, 비노출 판정분의 작성자 이미지는
 * 아카이브해 둘 이유가 없다.
 *
 * <p>PK가 (brand_id, short_code)라 같은 작성자가 여러 행(여러 게시물·여러 브랜드)에 걸쳐 등장할 수
 * 있다 — {@link HashtagPostThumbnailArchiveJob}이 short_code로 dedupe하듯, 여기는 <b>author_username
 * 단위</b>로 dedupe한다(다운로드는 작성자당 1회, UPDATE는 그 작성자의 미아카이브 행 전부).
 *
 * <p>썸네일 잡과 달리 후보 자체를 <b>미아카이브(author_image_object_path IS NULL) 행</b>으로
 * 좁힌다 — URL 변경 감지·재다운로드(source_name 비교) 로직은 두지 않는다(같은 작성자의 프로필
 * 사진이 스윕 사이 바뀌는 빈도가 게시물 썸네일보다 훨씬 낮고, 바뀌어도 다음 스윕에서 그 작성자의
 * 새 미아카이브 행이 생기면 자연히 재처리된다 — 태스크 범위를 최소로 유지).
 *
 * <p>배치 상한은 08-25 완전히 제거됐다 — 이력·근거는 {@link BrandPostThumbnailArchiveJob} 클래스
 * 주석 참고. 상한이 없으니 후보 전량이 매 스윕에서 처리된다.
 */
public class HashtagPostAuthorImageArchiveJob {

	private static final Logger log = LoggerFactory.getLogger(HashtagPostAuthorImageArchiveJob.class);

	static final String OBJECT_PREFIX = "monitor-hashtag-author/";
	// ProfileImageArchiveJob.CACHE_CONTROL과 동일한 값 — 동형 유지.
	static final String CACHE_CONTROL = "public, max-age=86400";

	private final JdbcTemplate db;
	private final ImageStore store;
	private final ImageDownloader downloader;
	private final String parUrl;

	public HashtagPostAuthorImageArchiveJob(JdbcTemplate db, ImageStore store, ImageDownloader downloader,
			String parUrl) {
		this.db = db;
		this.store = store;
		this.downloader = downloader;
		this.parUrl = parUrl;
	}

	public void run() {
		if (parUrl == null || parUrl.isBlank()) {
			log.info("MONITORING_IMAGE_PAR_URL 미설정 — 해시태그 게시물 작성자 이미지 아카이브 스킵(no-op)");
			return;
		}

		// DISTINCT ON: 같은 author_username이 여러 행일 때 1건만 — first_seen_at DESC로 최신 관측
		// URL(가장 늦게 발견된 행의 서명이 가장 늦게 만료)을 고른다. 미아카이브 행만 후보다.
		List<Candidate> candidates = db.query("""
				SELECT DISTINCT ON (author_username)
				       author_username, author_profile_pic_url
				FROM brand_hashtag_post
				WHERE verdict = 'RELEVANT' AND author_image_object_path IS NULL
				  AND author_profile_pic_url LIKE 'http%'
				ORDER BY author_username, first_seen_at DESC
				""", (rs, i) -> new Candidate(rs.getString("author_username"),
				rs.getString("author_profile_pic_url")));

		int archived = 0;
		int skipped = 0;
		int failed = 0;
		for (Candidate c : candidates) {
			String sourceName;
			try {
				sourceName = sourceName(c.profilePicUrl());
			} catch (RuntimeException e) {
				// 원본 URL 파싱 실패 — 이 건만 스킵하고 다음 스윕에서 재시도(다른 작성자는 계속 처리).
				log.warn("해시태그 작성자 프로필 URL 파싱 실패 — 스킵: authorUsername={}", c.authorUsername(), e);
				skipped++;
				continue;
			}
			try {
				ImageDownloader.Downloaded img = downloader.fetch(c.profilePicUrl());
				String objectPath = OBJECT_PREFIX + c.authorUsername() + ".jpg";
				store.put(objectPath, img.bytes(), img.contentType(), CACHE_CONTROL);
				// author_username 기준 — 그 작성자의 미아카이브 행 전부에 같은 경로를 채운다.
				db.update("""
						UPDATE brand_hashtag_post
						   SET author_image_object_path = ?, author_image_source_name = ?,
						       author_image_archived_at = now()
						 WHERE author_username = ? AND author_image_object_path IS NULL
						""", objectPath, sourceName, c.authorUsername());
				archived++;
			} catch (Exception e) {
				// 건 단위 격리 — 한 작성자의 다운로드/업로드 실패가 나머지 작성자를 막지 않는다.
				failed++;
				log.warn("해시태그 작성자 프로필 이미지 아카이브 실패 — authorUsername={}", c.authorUsername(), e);
			}
		}
		log.info("해시태그 작성자 프로필 이미지 아카이브 완료 — 아카이브 {}건 / 스킵 {}건 / 실패 {}건",
				archived, skipped, failed);
	}

	/**
	 * URL 경로의 마지막 세그먼트 — 쿼리스트링 제외({@link BrandPostThumbnailArchiveJob#sourceName}과
	 * 동일 관용구). 여기서는 재다운로드 판정에는 안 쓰이고 author_image_source_name 컬럼 기록용으로만 쓴다.
	 */
	static String sourceName(String url) {
		String path = URI.create(url).getPath();
		return path.substring(path.lastIndexOf('/') + 1);
	}

	private record Candidate(String authorUsername, String profilePicUrl) {
	}
}

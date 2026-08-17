package com.celfit.monitoring.image;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 게시자(인플루언서) 프로필 이미지 아카이브({@link ProfileImageArchiveJob}과 동형 — 대상만
 * {@code author_profile}) — 인스타 CDN이 만료(~4일)되기 전에 OCI 오브젝트 스토리지로 복사해 둔다.
 *
 * <p>author_profile은 30일 stale + 재등장 시에만 재조회된다(태그 스펙 §8) — 매일 upsert가 URL을
 * 되살리는 brand_post_meta와 달리, 아카이브 없이는 저장된 profile_pic_url이 대부분의 기간 죽어
 * 있으므로 이 잡이 유일한 서빙 가능 사본이다. 실행 시점은 {@link
 * com.celfit.monitoring.service.BrandSweepJob} 종료 직후(별도 크론 아님, 캠페인
 * {@code DailySweepJob}의 finally 패턴과 동형) — 스윕이 갓 재조회한 신선한 URL을 바로 잡기
 * 위함이며, 호출부가 이 잡의 실패를 스윕 결과와 격리한다. CDN이 이미 만료된 행(재조회 전
 * 잔존분)은 건 단위 실패로 격리되고, 다음 재조회가 URL을 되살리면 그때 아카이브된다.
 *
 * <p>키 스킴 {@code monitor-author/<ig_user_id>.jpg}는 ig_user_id(PK) 기준이다 — username은
 * 변경 가능해서 키로 쓰면 개명 시 고아 오브젝트가 남는다. 프리픽스는 {@link
 * ProfileImageArchiveJob}의 {@code monitor-profile/}, {@link PostThumbnailArchiveJob}의
 * {@code monitor-post/}와 분리해 같은 버킷 안에서 서로 덮어쓰지 않는다.
 */
public class AuthorProfileImageArchiveJob {

	private static final Logger log = LoggerFactory.getLogger(AuthorProfileImageArchiveJob.class);

	static final String OBJECT_PREFIX = "monitor-author/";
	// ProfileImageArchiveJob.CACHE_CONTROL과 동일한 값 — 동형 유지.
	static final String CACHE_CONTROL = "public, max-age=86400";

	private final JdbcTemplate db;
	private final ImageStore store;
	private final ImageDownloader downloader;
	private final String parUrl;
	private final int batchLimit;

	public AuthorProfileImageArchiveJob(JdbcTemplate db, ImageStore store, ImageDownloader downloader,
			String parUrl, int batchLimit) {
		this.db = db;
		this.store = store;
		this.downloader = downloader;
		this.parUrl = parUrl;
		this.batchLimit = batchLimit;
	}

	public void run() {
		if (parUrl == null || parUrl.isBlank()) {
			log.info("MONITORING_IMAGE_PAR_URL 미설정 — 게시자 프로필 이미지 아카이브 스킵(no-op)");
			return;
		}

		List<Candidate> candidates = db.query("""
				SELECT ig_user_id, profile_pic_url, image_object_path, image_source_name
				FROM author_profile
				WHERE profile_pic_url LIKE 'http%'
				""", (rs, i) -> new Candidate(rs.getString("ig_user_id"), rs.getString("profile_pic_url"),
				rs.getString("image_object_path"), rs.getString("image_source_name")));

		// 만료 URL은 시도해도 영원히 403 — 걸러내고 남은 예산은 만료 임박 순으로(근거는 CdnExpiry 주석).
		// author_profile은 30일 stale 때만 재조회돼 만료 잔존분 비중이 특히 크다(클래스 주석 참고).
		long nowEpoch = Instant.now().getEpochSecond();

		int archived = 0;
		int skipped = 0;
		int failed = 0;
		int expired = 0;
		int deferred = 0;
		for (Candidate c : CdnExpiry.soonestExpiryFirst(candidates, Candidate::profilePicUrl)) {
			String sourceName;
			try {
				sourceName = sourceName(c.profilePicUrl());
			} catch (RuntimeException e) {
				// 원본 URL 파싱 실패 — 이 건만 스킵하고 다음 스윕에서 재시도(다른 게시자는 계속 처리).
				// IllegalArgumentException뿐 아니라 RuntimeException 전부를 잡는다: 변종 URL은
				// LIKE 'http%'를 통과하면서도 getPath()가 null이라 NPE가 날 수 있고, 그게
				// 루프 밖으로 새면 이 잡 전체가 중단된다(ProfileImageArchiveJob과 동일 근거).
				log.warn("게시자 프로필 URL 파싱 실패 — 스킵: igUserId={}", c.igUserId(), e);
				skipped++;
				continue;
			}
			if (c.imageObjectPath() != null && sourceName.equals(c.imageSourceName())) {
				skipped++;   // 파일명 미변경 — 재다운로드 불필요(상한 미소모).
				continue;
			}
			if (CdnExpiry.isExpired(c.profilePicUrl(), nowEpoch)) {
				expired++;   // CDN 서명 만료 — 시도해도 403이라 예산을 쓰지 않는다(상한 미소모).
				continue;
			}
			if (archived + failed >= batchLimit) {
				deferred++;   // 다운로드 예산 소진 — 다음 스윕으로 이월.
				continue;
			}
			try {
				ImageDownloader.Downloaded img = downloader.fetch(c.profilePicUrl());
				String objectPath = OBJECT_PREFIX + c.igUserId() + ".jpg";
				store.put(objectPath, img.bytes(), img.contentType(), CACHE_CONTROL);
				db.update("""
						UPDATE author_profile
						   SET image_object_path = ?, image_source_name = ?, image_archived_at = now()
						 WHERE ig_user_id = ?
						""", objectPath, sourceName, c.igUserId());
				archived++;
			} catch (Exception e) {
				// 건 단위 격리 — 한 게시자의 다운로드/업로드 실패가 나머지 게시자를 막지 않는다.
				failed++;
				log.warn("게시자 프로필 이미지 아카이브 실패 — igUserId={}", c.igUserId(), e);
			}
		}
		log.info("게시자 프로필 이미지 아카이브 완료 — 아카이브 {}건 / 스킵 {}건 / 실패 {}건 / 만료 제외 {}건{}",
				archived, skipped, failed, expired, deferred > 0 ? ", 잔여 " + deferred + "건 이월" : "");
	}

	/**
	 * URL 경로의 마지막 세그먼트 — 쿼리스트링(인스타 CDN의 {@code oe=}·서명 파라미터)은 매 조회마다
	 * 바뀌므로 반드시 제외한다({@link ProfileImageArchiveJob#sourceName}과 동일 관용구). 안 그러면
	 * 재다운로드 판정이 매번 "변경됨"으로 나와 매일 전량 재다운로드하게 된다.
	 */
	static String sourceName(String url) {
		String path = URI.create(url).getPath();
		return path.substring(path.lastIndexOf('/') + 1);
	}

	private record Candidate(String igUserId, String profilePicUrl, String imageObjectPath, String imageSourceName) {
	}
}

package com.celfit.monitoring.image;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 모니터링 게시물 썸네일 아카이브(트랙 KK 확장 — {@link ProfileImageArchiveJob}과 동형) — {@code
 * post_meta} 전체를 대상으로, 인스타 CDN이 만료(~4일)되기 전에 OCI 오브젝트 스토리지로 복사해 둔다.
 *
 * <p>대상은 {@code target}이 아니라 {@code post_meta}다 — {@code post_meta}는 short_code PK로
 * target 상태와 무관하게 영구 존속하므로, {@code TargetRepository.findActive()}를 전혀 건드리지
 * 않고도 CANCELED/EXPIRED로 종료된 캠페인의 추적 게시물까지 자동으로 커버된다. 실행 시점은 {@link
 * com.celfit.monitoring.service.DailySweepJob} 종료 직후(별도 크론 아님, {@link ProfileImageArchiveJob}과
 * 같은 finally 블록) — 호출부가 이 잡의 실패를 스윕 결과(sweep_run)와 격리한다.
 *
 * <p>키 스킴 {@code monitor-post/<short_code>.jpg}는 {@link ProfileImageArchiveJob}의
 * {@code monitor-profile/<username>.jpg}, analytics {@code ImageArchiveJob}의
 * {@code thumb/<shortCode>.jpg}와 프리픽스를 분리한다 — 같은 오브젝트 스토리지 버킷 안에서 세 잡이
 * 서로 덮어쓰지 않게 하기 위함이다.
 */
public class PostThumbnailArchiveJob {

	private static final Logger log = LoggerFactory.getLogger(PostThumbnailArchiveJob.class);

	static final String OBJECT_PREFIX = "monitor-post/";
	// ProfileImageArchiveJob.CACHE_CONTROL과 동일한 값 — 동형 유지(설계 판단은 이 클래스 주석·PR 본문 참고).
	static final String CACHE_CONTROL = "public, max-age=86400";

	private final JdbcTemplate db;
	private final ImageStore store;
	private final ImageDownloader downloader;
	private final String parUrl;
	private final int batchLimit;

	public PostThumbnailArchiveJob(JdbcTemplate db, ImageStore store, ImageDownloader downloader,
			String parUrl, int batchLimit) {
		this.db = db;
		this.store = store;
		this.downloader = downloader;
		this.parUrl = parUrl;
		this.batchLimit = batchLimit;
	}

	public void run() {
		if (parUrl == null || parUrl.isBlank()) {
			log.info("MONITORING_IMAGE_PAR_URL 미설정 — 게시물 썸네일 아카이브 스킵(no-op)");
			return;
		}

		List<Candidate> candidates = db.query("""
				SELECT short_code, thumbnail_url, image_object_path, image_source_name
				FROM post_meta
				WHERE thumbnail_url LIKE 'http%'
				""", (rs, i) -> new Candidate(rs.getString("short_code"), rs.getString("thumbnail_url"),
				rs.getString("image_object_path"), rs.getString("image_source_name")));

		// 만료 URL은 시도해도 영원히 403 — 걸러내고 남은 예산은 만료 임박 순으로(근거는 CdnExpiry 주석).
		long nowEpoch = Instant.now().getEpochSecond();

		int archived = 0;
		int skipped = 0;
		int failed = 0;
		int expired = 0;
		int deferred = 0;
		for (Candidate c : CdnExpiry.soonestExpiryFirst(candidates, Candidate::thumbnailUrl)) {
			String sourceName;
			try {
				sourceName = sourceName(c.thumbnailUrl());
			} catch (RuntimeException e) {
				// 원본 URL 파싱 실패 — 이 건만 스킵하고 다음 스윕에서 재시도(다른 게시물은 계속 처리).
				// IllegalArgumentException뿐 아니라 RuntimeException 전부를 잡는다: 스킴 정규화 이전에
				// 저장된 변종은 LIKE 'http%'를 통과하면서도 getPath()가 null이라 NPE가 날 수 있고, 그게
				// 루프 밖으로 새면 이 잡 전체가 중단된다(ProfileImageArchiveJob과 동일 근거).
				log.warn("썸네일 URL 파싱 실패 — 스킵: shortCode={}", c.shortCode(), e);
				skipped++;
				continue;
			}
			if (c.imageObjectPath() != null && sourceName.equals(c.imageSourceName())) {
				skipped++;   // 파일명 미변경 — 재다운로드 불필요(상한 미소모).
				continue;
			}
			if (CdnExpiry.isExpired(c.thumbnailUrl(), nowEpoch)) {
				expired++;   // CDN 서명 만료 — 시도해도 403이라 예산을 쓰지 않는다(상한 미소모).
				continue;
			}
			if (archived + failed >= batchLimit) {
				deferred++;   // 다운로드 예산 소진 — 다음 스윕으로 이월.
				continue;
			}
			try {
				ImageDownloader.Downloaded img = downloader.fetch(c.thumbnailUrl());
				String objectPath = OBJECT_PREFIX + c.shortCode() + ".jpg";
				store.put(objectPath, img.bytes(), img.contentType(), CACHE_CONTROL);
				db.update("""
						UPDATE post_meta
						   SET image_object_path = ?, image_source_name = ?, image_archived_at = now()
						 WHERE short_code = ?
						""", objectPath, sourceName, c.shortCode());
				archived++;
			} catch (Exception e) {
				// 건 단위 격리 — 한 게시물의 다운로드/업로드 실패가 나머지 게시물을 막지 않는다.
				failed++;
				log.warn("게시물 썸네일 아카이브 실패 — shortCode={}", c.shortCode(), e);
			}
		}
		log.info("게시물 썸네일 아카이브 완료 — 아카이브 {}건 / 스킵 {}건 / 실패 {}건 / 만료 제외 {}건{}",
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

	private record Candidate(String shortCode, String thumbnailUrl, String imageObjectPath, String imageSourceName) {
	}
}

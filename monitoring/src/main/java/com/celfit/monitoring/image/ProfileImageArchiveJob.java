package com.celfit.monitoring.image;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 모니터링 프로필 이미지 아카이브(설계 스펙 §3-1, 결함 ①) — {@code profile_meta} 전체를 대상으로,
 * 인스타 CDN이 만료(~4일)되기 전에 OCI 오브젝트 스토리지로 복사해 둔다.
 *
 * <p>대상은 {@code target}이 아니라 {@code profile_meta}다 — {@code profile_meta}는 username PK로
 * target 상태와 무관하게 영구 존속하므로, {@code TargetRepository.findActive()}를 전혀 건드리지
 * 않고도 CANCELED/EXPIRED로 종료된 항목까지 자동으로 커버된다. 실행 시점은 {@link
 * com.celfit.monitoring.service.DailySweepJob} 종료 직후(별도 크론 아님) — 스윕이 갓 갱신한 신선한
 * URL을 바로 잡기 위함이며, 호출부(DailySweepJob)가 이 잡의 실패를 스윕 결과(sweep_run)와
 * 격리한다.
 *
 * <p>키 스킴 {@code monitor-profile/<username>.jpg}는 analytics {@code ImageArchiveJob}의
 * {@code profile/<handle>.jpg}와 프리픽스를 분리한다 — 같은 오브젝트 스토리지 버킷 안에서 두 잡이
 * 핸들이 겹쳐도 서로 덮어쓰지 않게 하기 위함이다.
 */
public class ProfileImageArchiveJob {

	private static final Logger log = LoggerFactory.getLogger(ProfileImageArchiveJob.class);

	static final String OBJECT_PREFIX = "monitor-profile/";
	// analytics ImageArchiveJob.PROFILE_CACHE_CONTROL과 동일한 값(설계 스펙 §3-1 "Cache-Control ... 그대로 맞춰라").
	static final String CACHE_CONTROL = "public, max-age=86400";

	private final JdbcTemplate db;
	private final ImageStore store;
	private final ImageDownloader downloader;
	private final String parUrl;
	private final int batchLimit;

	public ProfileImageArchiveJob(JdbcTemplate db, ImageStore store, ImageDownloader downloader,
			String parUrl, int batchLimit) {
		this.db = db;
		this.store = store;
		this.downloader = downloader;
		this.parUrl = parUrl;
		this.batchLimit = batchLimit;
	}

	public void run() {
		if (parUrl == null || parUrl.isBlank()) {
			log.info("MONITORING_IMAGE_PAR_URL 미설정 — 프로필 이미지 아카이브 스킵(no-op)");
			return;
		}

		List<Candidate> candidates = db.query("""
				SELECT username, profile_image_url, image_object_path, image_source_name
				FROM profile_meta
				WHERE profile_image_url LIKE 'http%'
				""", (rs, i) -> new Candidate(rs.getString("username"), rs.getString("profile_image_url"),
				rs.getString("image_object_path"), rs.getString("image_source_name")));

		// 만료 URL은 시도해도 영원히 403 — 걸러내고 남은 예산은 만료 임박 순으로(근거는 CdnExpiry 주석).
		long nowEpoch = Instant.now().getEpochSecond();

		int archived = 0;
		int skipped = 0;
		int failed = 0;
		int expired = 0;
		int deferred = 0;
		for (CdnExpiry.Ranked<Candidate> r : CdnExpiry.soonestExpiryFirst(candidates, Candidate::profileImageUrl)) {
			Candidate c = r.item();
			String sourceName;
			try {
				sourceName = sourceName(c.profileImageUrl());
			} catch (RuntimeException e) {
				// 원본 URL 파싱 실패 — 이 건만 스킵하고 다음 스윕에서 재시도(다른 계정은 계속 처리).
				// IllegalArgumentException뿐 아니라 RuntimeException 전부를 잡는다: PR-1(스킴 정규화) 이전에
				// 저장된 변종은 LIKE 'http%'를 통과하면서도 getPath()가 null이라 NPE가 날 수 있고, 그게
				// 루프 밖으로 새면 이 잡 전체가 중단된다.
				log.warn("프로필 URL 파싱 실패 — 스킵: username={}", c.username(), e);
				skipped++;
				continue;
			}
			if (c.imageObjectPath() != null && sourceName.equals(c.imageSourceName())) {
				skipped++;   // 파일명 미변경 — 재다운로드 불필요(상한 미소모).
				continue;
			}
			if (r.expired(nowEpoch)) {
				expired++;   // CDN 서명 만료 — 시도해도 403이라 예산을 쓰지 않는다(상한 미소모).
				continue;
			}
			if (archived + failed >= batchLimit) {
				deferred++;   // 다운로드 예산 소진 — 다음 스윕으로 이월.
				continue;
			}
			try {
				ImageDownloader.Downloaded img = downloader.fetch(c.profileImageUrl());
				String objectPath = OBJECT_PREFIX + c.username() + ".jpg";
				store.put(objectPath, img.bytes(), img.contentType(), CACHE_CONTROL);
				db.update("""
						UPDATE profile_meta
						   SET image_object_path = ?, image_source_name = ?, image_archived_at = now()
						 WHERE username = ?
						""", objectPath, sourceName, c.username());
				archived++;
			} catch (Exception e) {
				// 건 단위 격리 — 한 계정의 다운로드/업로드 실패가 나머지 계정을 막지 않는다.
				failed++;
				log.warn("프로필 이미지 아카이브 실패 — username={}", c.username(), e);
			}
		}
		log.info("프로필 이미지 아카이브 완료 — 아카이브 {}건 / 스킵 {}건 / 실패 {}건 / 만료 제외 {}건{}",
				archived, skipped, failed, expired, deferred > 0 ? ", 잔여 " + deferred + "건 이월" : "");
	}

	/**
	 * URL 경로의 마지막 세그먼트 — 쿼리스트링(인스타 CDN의 {@code oe=}·서명 파라미터)은 매 조회마다
	 * 바뀌므로 반드시 제외한다(analytics {@code ImageArchiveJob.sourceName}과 동일 관용구). 안 그러면
	 * 재다운로드 판정이 매번 "변경됨"으로 나와 매일 전량 재다운로드하게 된다.
	 */
	static String sourceName(String url) {
		String path = URI.create(url).getPath();
		return path.substring(path.lastIndexOf('/') + 1);
	}

	private record Candidate(String username, String profileImageUrl, String imageObjectPath, String imageSourceName) {
	}
}

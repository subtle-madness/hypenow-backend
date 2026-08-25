package com.celfit.monitoring.image;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 브랜드 태그 게시물 썸네일 아카이브({@link PostThumbnailArchiveJob}과 동형, 트랙 KK 확장) —
 * {@code brand_post_meta} 전체를 대상으로, 인스타 CDN이 만료(~4일)되기 전에 OCI 오브젝트
 * 스토리지로 복사해 둔다. 실행 시점은 {@link com.celfit.monitoring.service.BrandSweepJob} 종료
 * 직후 finally(게시자·브랜드 프로필 아카이브와 나란히) — 호출부가 실패를 스윕 결과와 격리한다.
 *
 * <p>키 스킴 {@code monitor-brand-post/<short_code>.jpg}는 캠페인 {@code monitor-post/}와
 * 프리픽스를 분리한다 — 같은 버킷 안에서 잡끼리 서로 덮어쓰지 않게 하기 위함이다.
 *
 * <p>배치 상한은 08-25 완전히 제거됐다(08-25 운영 진단 — author_profile 30,362건 중 46.9%가
 * 상한 1,000/일에 정체, 밀린 행은 인스타 CDN 서명 만료로 영구 유실). 도입 당시(08-12) 이 잡만
 * "다운로드 시도(성공·실패)만 상한을 소모"하는 방식이었다 — 후보 리스트를 상한에서 먼저 자르면
 * "이미 아카이브됨" 스킵 행이 창을 잠식해 미아카이브 꼬리에 도달하지 못하는 문제(author_profile
 * 백로그 2,675건이 상한 1,000에도 5일째 잔존)가 있었기 때문. 상한 자체가 없어진 지금은 후보
 * 전량이 매 스윕에서 처리된다 — 만료 필터(CdnExpiry)와 건 단위 실패 격리만으로 안전성을 유지한다.
 */
public class BrandPostThumbnailArchiveJob {

	private static final Logger log = LoggerFactory.getLogger(BrandPostThumbnailArchiveJob.class);

	static final String OBJECT_PREFIX = "monitor-brand-post/";
	// ProfileImageArchiveJob.CACHE_CONTROL과 동일한 값 — 동형 유지.
	static final String CACHE_CONTROL = "public, max-age=86400";

	private final JdbcTemplate db;
	private final ImageStore store;
	private final ImageDownloader downloader;
	private final String parUrl;

	public BrandPostThumbnailArchiveJob(JdbcTemplate db, ImageStore store, ImageDownloader downloader,
			String parUrl) {
		this.db = db;
		this.store = store;
		this.downloader = downloader;
		this.parUrl = parUrl;
	}

	public void run() {
		if (parUrl == null || parUrl.isBlank()) {
			log.info("MONITORING_IMAGE_PAR_URL 미설정 — 브랜드 게시물 썸네일 아카이브 스킵(no-op)");
			return;
		}

		List<Candidate> candidates = db.query("""
				SELECT short_code, thumbnail_url, image_object_path, image_source_name
				FROM brand_post_meta
				WHERE thumbnail_url LIKE 'http%'
				""", (rs, i) -> new Candidate(rs.getString("short_code"), rs.getString("thumbnail_url"),
				rs.getString("image_object_path"), rs.getString("image_source_name")));

		// 만료 URL은 재시도해도 영원히 403이라 시도 자체를 걸러내고, 남은 예산은 만료 임박 순으로 쓴다
		// (실측 근거는 CdnExpiry 클래스 주석 — 이 잡이 그 실측 대상이다). 재조회가 URL을 갱신하면
		// 만료가 미래가 돼 자동으로 후보에 복귀한다 — 단 재열거 대상일 때만: TRACKED_MAX_AGE(180일)
		// 초과분은 재열거가 없어 복귀하지 않고, tier3/4(재크롤 7·30일 주기 vs 서명 수명 ~4일)는
		// 살아있는 창이 간헐적이다. 즉 "만료 제외" 수치의 일부는 영구 유실분이다(BrandCrawlPolicy 참조).
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
				// RuntimeException 전부를 잡는 근거는 PostThumbnailArchiveJob과 동일: 변종 URL은
				// LIKE 'http%'를 통과하면서도 getPath()가 null이라 NPE가 날 수 있다.
				log.warn("브랜드 게시물 썸네일 URL 파싱 실패 — 스킵: shortCode={}", c.shortCode(), e);
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
				db.update("""
						UPDATE brand_post_meta
						   SET image_object_path = ?, image_source_name = ?, image_archived_at = now()
						 WHERE short_code = ?
						""", objectPath, sourceName, c.shortCode());
				archived++;
			} catch (Exception e) {
				// 건 단위 격리 — 한 게시물의 다운로드/업로드 실패가 나머지 게시물을 막지 않는다.
				failed++;
				log.warn("브랜드 게시물 썸네일 아카이브 실패 — shortCode={}", c.shortCode(), e);
			}
		}
		log.info("브랜드 게시물 썸네일 아카이브 완료 — 아카이브 {}건 / 스킵 {}건 / 실패 {}건 / 만료 제외 {}건",
				archived, skipped, failed, expired);
	}

	/**
	 * URL 경로의 마지막 세그먼트 — 쿼리스트링(인스타 CDN의 {@code oe=}·서명 파라미터)은 매 조회마다
	 * 바뀌므로 반드시 제외한다({@link PostThumbnailArchiveJob#sourceName}과 동일 관용구). 안 그러면
	 * 재다운로드 판정이 매번 "변경됨"으로 나와 매일 전량 재다운로드하게 된다.
	 */
	static String sourceName(String url) {
		String path = URI.create(url).getPath();
		return path.substring(path.lastIndexOf('/') + 1);
	}

	private record Candidate(String shortCode, String thumbnailUrl, String imageObjectPath, String imageSourceName) {
	}
}

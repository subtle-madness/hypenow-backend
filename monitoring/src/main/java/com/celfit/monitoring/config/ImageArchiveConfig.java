package com.celfit.monitoring.config;

import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.image.AuthorImageBackfillJob;
import com.celfit.monitoring.image.AuthorProfileImageArchiveJob;
import com.celfit.monitoring.image.BrandPostThumbnailArchiveJob;
import com.celfit.monitoring.image.BrandProfileImageArchiveJob;
import com.celfit.monitoring.image.GcsImageStore;
import com.celfit.monitoring.image.HashtagPostAuthorImageArchiveJob;
import com.celfit.monitoring.image.HashtagPostThumbnailArchiveJob;
import com.celfit.monitoring.image.ImageDownloader;
import com.celfit.monitoring.image.ImageResizer;
import com.celfit.monitoring.image.ImageStore;
import com.celfit.monitoring.image.ParImageStore;
import com.celfit.monitoring.image.PostThumbnailArchiveJob;
import com.celfit.monitoring.image.ProfileImageArchiveJob;
import com.celfit.monitoring.store.AuthorProfileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 이미지 아카이브 잡 배선(설계 스펙 §3-1, 트랙 KK 확장) — {@code monitoring.image.par-url} 미설정이면
 * {@link ParImageStore}는 예외 없이 빈 URL을 든 채로 만들어지고(기동 실패 방지), 실제 no-op 판단은
 * 각 잡의 {@code run()}이 PAR URL 자체를 보고 내린다. gcs 모드에선 {@code monitoring.image.gcs-bucket}
 * blank가 같은 역할을 한다. 모든 잡은 같은 버킷·같은 스토어 설정을 재사용한다(키 프리픽스로 소유권만
 * 분리 — monitor-profile/ · monitor-post/ · monitor-author/ · monitor-brand/ · monitor-brand-post/ ·
 * monitor-hashtag-post/ · monitor-hashtag-author/).
 */
@Configuration
public class ImageArchiveConfig {

	// IMAGE_STORE=gcs|par — 6개 잡 빈이 공유하는 스토어 선택(2026-08-12 GCS 이전).
	// gcs 모드에선 잡의 no-op 판단 인자(기존 parUrl 슬롯)에 버킷명을 넘긴다 — blank면 no-op.
	private static ImageStore imageStore(String storeMode, String parUrl, String gcsBucket, String gcsKey) {
		return "gcs".equalsIgnoreCase(storeMode)
				? new GcsImageStore(gcsBucket, gcsKey) : new ParImageStore(parUrl);
	}

	private static String storeTarget(String storeMode, String parUrl, String gcsBucket) {
		return "gcs".equalsIgnoreCase(storeMode) ? gcsBucket : parUrl;
	}

	@Bean
	public ProfileImageArchiveJob profileImageArchiveJob(JdbcTemplate db,
			@Value("${monitoring.image.par-url:}") String parUrl,
			@Value("${monitoring.image.store:par}") String storeMode,
			@Value("${monitoring.image.gcs-bucket:}") String gcsBucket,
			@Value("${monitoring.image.gcs-key:}") String gcsKey) {
		return new ProfileImageArchiveJob(db, imageStore(storeMode, parUrl, gcsBucket, gcsKey),
				ImageResizer.wrap(ImageDownloader.http()), storeTarget(storeMode, parUrl, gcsBucket));
	}

	@Bean
	public PostThumbnailArchiveJob postThumbnailArchiveJob(JdbcTemplate db,
			@Value("${monitoring.image.par-url:}") String parUrl,
			@Value("${monitoring.image.store:par}") String storeMode,
			@Value("${monitoring.image.gcs-bucket:}") String gcsBucket,
			@Value("${monitoring.image.gcs-key:}") String gcsKey) {
		return new PostThumbnailArchiveJob(db, imageStore(storeMode, parUrl, gcsBucket, gcsKey),
				ImageResizer.wrap(ImageDownloader.http()), storeTarget(storeMode, parUrl, gcsBucket));
	}

	/** 브랜드 태그 파이프라인의 게시자 프로필 사진 — 같은 버킷, 프리픽스만 monitor-author/로 분리. */
	@Bean
	public AuthorProfileImageArchiveJob authorProfileImageArchiveJob(JdbcTemplate db,
			@Value("${monitoring.image.par-url:}") String parUrl,
			@Value("${monitoring.image.store:par}") String storeMode,
			@Value("${monitoring.image.gcs-bucket:}") String gcsBucket,
			@Value("${monitoring.image.gcs-key:}") String gcsKey) {
		return new AuthorProfileImageArchiveJob(db, imageStore(storeMode, parUrl, gcsBucket, gcsKey),
				ImageResizer.wrap(ImageDownloader.http()), storeTarget(storeMode, parUrl, gcsBucket));
	}

	/** 브랜드 본인 프로필 사진 — 같은 버킷, 프리픽스만 monitor-brand/로 분리. */
	@Bean
	public BrandProfileImageArchiveJob brandProfileImageArchiveJob(JdbcTemplate db,
			@Value("${monitoring.image.par-url:}") String parUrl,
			@Value("${monitoring.image.store:par}") String storeMode,
			@Value("${monitoring.image.gcs-bucket:}") String gcsBucket,
			@Value("${monitoring.image.gcs-key:}") String gcsKey) {
		return new BrandProfileImageArchiveJob(db, imageStore(storeMode, parUrl, gcsBucket, gcsKey),
				ImageResizer.wrap(ImageDownloader.http()), storeTarget(storeMode, parUrl, gcsBucket));
	}

	/** 브랜드 태그 게시물 썸네일 — 같은 버킷, 프리픽스만 monitor-brand-post/로 분리. */
	@Bean
	public BrandPostThumbnailArchiveJob brandPostThumbnailArchiveJob(JdbcTemplate db,
			@Value("${monitoring.image.par-url:}") String parUrl,
			@Value("${monitoring.image.store:par}") String storeMode,
			@Value("${monitoring.image.gcs-bucket:}") String gcsBucket,
			@Value("${monitoring.image.gcs-key:}") String gcsKey) {
		return new BrandPostThumbnailArchiveJob(db, imageStore(storeMode, parUrl, gcsBucket, gcsKey),
				ImageResizer.wrap(ImageDownloader.http()), storeTarget(storeMode, parUrl, gcsBucket));
	}

	/** 해시태그 발견 게시물 썸네일(RELEVANT만) — 같은 버킷, 프리픽스만 monitor-hashtag-post/로 분리. */
	@Bean
	public HashtagPostThumbnailArchiveJob hashtagPostThumbnailArchiveJob(JdbcTemplate db,
			@Value("${monitoring.image.par-url:}") String parUrl,
			@Value("${monitoring.image.store:par}") String storeMode,
			@Value("${monitoring.image.gcs-bucket:}") String gcsBucket,
			@Value("${monitoring.image.gcs-key:}") String gcsKey) {
		return new HashtagPostThumbnailArchiveJob(db, imageStore(storeMode, parUrl, gcsBucket, gcsKey),
				ImageResizer.wrap(ImageDownloader.http()), storeTarget(storeMode, parUrl, gcsBucket));
	}

	/** 해시태그 발견 게시물 작성자 프로필 사진(RELEVANT만) — 같은 버킷, 프리픽스만 monitor-hashtag-author/로 분리. */
	@Bean
	public HashtagPostAuthorImageArchiveJob hashtagPostAuthorImageArchiveJob(JdbcTemplate db,
			@Value("${monitoring.image.par-url:}") String parUrl,
			@Value("${monitoring.image.store:par}") String storeMode,
			@Value("${monitoring.image.gcs-bucket:}") String gcsBucket,
			@Value("${monitoring.image.gcs-key:}") String gcsKey) {
		return new HashtagPostAuthorImageArchiveJob(db, imageStore(storeMode, parUrl, gcsBucket, gcsKey),
				ImageResizer.wrap(ImageDownloader.http()), storeTarget(storeMode, parUrl, gcsBucket));
	}

	/**
	 * 만료된 CDN 프로필 이미지 재수집 백필(2026-08-25, 어드민 수동 트리거 전용 — AuthorImageBackfillController
	 * 참고) — 위 두 잡(authorProfileImageArchiveJob·hashtagPostAuthorImageArchiveJob) 빈을 그대로
	 * 재사용해 백필 직후 같은 실행에서 다운로드·업로드까지 닫는다.
	 */
	@Bean
	public AuthorImageBackfillJob authorImageBackfillJob(JdbcTemplate db, HikerClient hikerClient,
			AuthorProfileRepository authorProfileRepo, AuthorProfileImageArchiveJob authorProfileImageArchiveJob,
			HashtagPostAuthorImageArchiveJob hashtagPostAuthorImageArchiveJob) {
		return new AuthorImageBackfillJob(db, hikerClient, authorProfileRepo, authorProfileImageArchiveJob,
				hashtagPostAuthorImageArchiveJob);
	}
}

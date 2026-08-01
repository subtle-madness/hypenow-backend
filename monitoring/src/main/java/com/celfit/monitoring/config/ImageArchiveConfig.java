package com.celfit.monitoring.config;

import com.celfit.monitoring.image.ImageDownloader;
import com.celfit.monitoring.image.ParImageStore;
import com.celfit.monitoring.image.PostThumbnailArchiveJob;
import com.celfit.monitoring.image.ProfileImageArchiveJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 이미지 아카이브 잡 배선(설계 스펙 §3-1, 트랙 KK 확장) — {@code monitoring.image.par-url} 미설정이면
 * {@link ParImageStore}는 예외 없이 빈 URL을 든 채로 만들어지고(기동 실패 방지), 실제 no-op 판단은
 * 각 잡의 {@code run()}이 PAR URL 자체를 보고 내린다. 프로필 이미지·게시물 썸네일 두 잡은 같은 OCI
 * 버킷·같은 쓰기 PAR을 재사용한다(키 프리픽스로 소유권만 분리 — monitor-profile/ vs monitor-post/).
 */
@Configuration
public class ImageArchiveConfig {

	@Bean
	public ProfileImageArchiveJob profileImageArchiveJob(JdbcTemplate db,
			@Value("${monitoring.image.par-url:}") String parUrl,
			@Value("${monitoring.image.archive-batch-limit:1000}") int batchLimit) {
		return new ProfileImageArchiveJob(db, new ParImageStore(parUrl), ImageDownloader.http(), parUrl, batchLimit);
	}

	@Bean
	public PostThumbnailArchiveJob postThumbnailArchiveJob(JdbcTemplate db,
			@Value("${monitoring.image.par-url:}") String parUrl,
			@Value("${monitoring.image.archive-batch-limit:1000}") int batchLimit) {
		return new PostThumbnailArchiveJob(db, new ParImageStore(parUrl), ImageDownloader.http(), parUrl, batchLimit);
	}
}

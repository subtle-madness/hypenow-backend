package com.celfit.analytics.archive;

import com.celfit.analytics.analyze.JobResult;
import com.celfit.analytics.analyze.ProgressReporter;
import com.celfit.analytics.config.AnalyticsSettings;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 서빙 이미지 아카이브 잡 (태스크 J) — CDN 만료(~4일) 전에 썸네일·프로필을 오브젝트 스토리지로.
 * 대상 선정: 썸네일=image_assets 미기록 shortCode만(1회 불변 — 중복 수집분 다운로드 생략),
 * 프로필=원본 URL 파일명(source_name)이 바뀐 계정만(같은 키 덮어쓰기 — 축적 없음).
 * 실패는 건 단위 격리(미기록 → 다음 실행 재대상), 배치 상한 초과분은 이월(carriedOver).
 */
public class ImageArchiveJob {

	private static final Logger log = LoggerFactory.getLogger(ImageArchiveJob.class);

	static final String KIND_THUMBNAIL = "thumbnail";
	static final String KIND_PROFILE = "profile";
	static final String THUMB_CACHE_CONTROL = "public, max-age=31536000, immutable";
	static final String PROFILE_CACHE_CONTROL = "public, max-age=86400";

	private final JdbcTemplate raw;
	private final JdbcTemplate analysis;
	private final ImageStore store;
	private final ImageDownloader downloader;
	private final AnalyticsSettings settings;
	private final ProgressReporter reporter;

	public ImageArchiveJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			ImageStore store, ImageDownloader downloader, AnalyticsSettings settings,
			ProgressReporter reporter) {
		this.raw = rawJdbcTemplate;
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.store = store;
		this.downloader = downloader;
		this.settings = settings;
		this.reporter = reporter;
	}

	public JobResult run() {
		List<Target> targets = new ArrayList<>(profileTargets());
		targets.addAll(thumbnailTargets());
		int limit = settings.archiveBatchLimit();
		boolean carriedOver = targets.size() > limit;
		List<Target> batch = targets.subList(0, Math.min(limit, targets.size()));

		int done = 0;
		int failed = 0;
		reporter.report(0, 0, batch.size());
		for (Target t : batch) {
			try {
				ImageDownloader.Downloaded img = downloader.fetch(t.url());
				store.put(t.objectPath(), img.bytes(), img.contentType(),
						KIND_PROFILE.equals(t.kind()) ? PROFILE_CACHE_CONTROL : THUMB_CACHE_CONTROL);
				analysis.update("""
						INSERT INTO image_assets (kind, key, object_path, source_name)
						VALUES (?, ?, ?, ?)
						ON CONFLICT (kind, key) DO UPDATE
						  SET object_path = EXCLUDED.object_path,
						      source_name = EXCLUDED.source_name,
						      archived_at = now()
						""", t.kind(), t.key(), t.objectPath(), sourceName(t.url()));
				done++;
			} catch (Exception e) {
				failed++;
				log.warn("이미지 아카이브 실패: {} {}", t.kind(), t.key(), e);
			}
			reporter.report(done, failed, batch.size());
		}
		log.info("이미지 아카이브 완료 — {}건 저장, {}건 실패{}", done, failed,
				carriedOver ? ", 잔여 " + (targets.size() - batch.size()) + "건 이월" : "");
		return new JobResult(done, failed, carriedOver);
	}

	record Target(String kind, String key, String url) {

		String objectPath() {
			return (KIND_PROFILE.equals(kind) ? "profile/" : "thumb/") + key + ".jpg";
		}
	}

	private List<Target> thumbnailTargets() {
		Set<String> archived = new HashSet<>(analysis.queryForList(
				"SELECT key FROM image_assets WHERE kind = 'thumbnail'", String.class));
		return raw.query("""
				SELECT short_code, thumbnail_url FROM analytics.v_contents
				WHERE thumbnail_url IS NOT NULL
				""", (rs, i) -> new Target(KIND_THUMBNAIL, rs.getString(1), rs.getString(2)))
				.stream().filter(t -> !archived.contains(t.key())).toList();
	}

	private List<Target> profileTargets() {
		Map<String, String> archived = new HashMap<>();
		analysis.query("SELECT key, source_name FROM image_assets WHERE kind = 'profile'",
				rs -> {
					archived.put(rs.getString(1), rs.getString(2));
				});
		return raw.query("""
				SELECT handle, profile_image_url FROM analytics.v_accounts
				WHERE profile_image_url IS NOT NULL
				""", (rs, i) -> new Target(KIND_PROFILE, rs.getString(1), rs.getString(2)))
				.stream().filter(t -> profileChanged(t, archived.get(t.key()))).toList();
	}

	/** 파일명 비교 — URL이 파싱 불가면 '변경'으로 간주해 대상에 남긴다(실패는 run()의 건 단위 격리가 흡수). */
	private boolean profileChanged(Target t, String archivedSourceName) {
		try {
			return !sourceName(t.url()).equals(archivedSourceName);
		} catch (IllegalArgumentException e) {
			log.warn("프로필 URL 파싱 실패 — 재시도 대상으로 유지: {}", t.key(), e);
			return true;
		}
	}

	/** URL 경로의 마지막 세그먼트(인스타 미디어 ID 파일명) — 호스트·서명 쿼리는 크롤마다 바뀌므로 제외. */
	static String sourceName(String url) {
		String path = URI.create(url).getPath();
		return path.substring(path.lastIndexOf('/') + 1);
	}
}

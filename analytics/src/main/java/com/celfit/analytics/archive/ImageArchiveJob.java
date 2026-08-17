package com.celfit.analytics.archive;

import com.celfit.analytics.analyze.JobResult;
import com.celfit.analytics.analyze.ProgressReporter;
import com.celfit.analytics.config.AnalyticsSettings;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
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
		List<Target> all = new ArrayList<>(profileTargets());
		all.addAll(thumbnailTargets());
		// CDN 서명 만료(oe) 판정 — 만료 URL은 재시도해도 영원히 403이라 시도 자체를 걸러낸다
		// (재크롤로 URL이 갱신되면 oe가 미래가 돼 자동 복귀). 영구 무효 URL도 같은 이유로 제외
		// (permanentlyInvalid 주석 참고). 남은 대상은 만료 임박 순.
		long nowEpoch = Instant.now().getEpochSecond();
		Map<Target, Long> expiry = new HashMap<>();
		List<Target> targets = new ArrayList<>();
		int expiredSkipped = 0;
		int invalidSkipped = 0;
		for (Target t : all) {
			if (permanentlyInvalid(t.url())) {
				invalidSkipped++;
				continue;
			}
			Long oe = expiryEpoch(t.url());
			if (oe != null && oe <= nowEpoch) {
				expiredSkipped++;
			} else {
				targets.add(t);
				expiry.put(t, oe);
			}
		}
		targets.sort(Comparator.comparing(expiry::get,
				Comparator.nullsLast(Comparator.naturalOrder())));
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
		log.info("이미지 아카이브 완료 — {}건 저장, {}건 실패, 만료 제외 {}건, 무효 제외 {}건{}",
				done, failed, expiredSkipped, invalidSkipped,
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
				SELECT short_code, thumbnail_url FROM v_contents
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
				SELECT handle, profile_image_url FROM v_accounts
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

	/**
	 * 영구 무효 URL — 시도해도 항상 실패라 후보에서 제외한다(08-16 운영 실측: 이 부류 15건이 매일
	 * 재시도·실패하며 매 실행을 FAILED로 만들었다 — 어드민 카드 "실패" 뱃지의 원인).
	 * ① http(s) 아닌 스킴: Hiker 업스트림이 URL 자리에 넣는 리터럴 {@code exception://} 센티널 부류
	 *   — monitoring {@code ProfileMetaRepository.normalizeImageUrl}과 같은 규칙(트랙 KK 결함②).
	 * ② {@code rsrc.php/null.jpg}: 삭제·비공개 미디어에 IG가 주는 플레이스홀더 — 영구 HTTP 400.
	 * 만료 제외와 마찬가지로 "실패"가 아니라 "제외"다 — 후보 선정이 매 실행 원본 뷰 기준이라,
	 * 재크롤이 정상 URL을 주면 자연히 후보로 복귀한다.
	 */
	static boolean permanentlyInvalid(String url) {
		String lower = url.toLowerCase(java.util.Locale.ROOT);
		if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
			return true;
		}
		return lower.endsWith("rsrc.php/null.jpg");
	}

	/** 인스타 CDN 서명 만료 시각(oe 파라미터, hex unix 초) — 없거나 파싱 불가면 null(만료 미상 → 시도 유지). */
	static Long expiryEpoch(String url) {
		var m = OE_PARAM.matcher(url);
		if (!m.find()) {
			return null;
		}
		try {
			return Long.parseLong(m.group(1), 16);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static final Pattern OE_PARAM = Pattern.compile("[?&]oe=([0-9A-Fa-f]{1,15})(?:&|$)");

	/** URL 경로의 마지막 세그먼트(인스타 미디어 ID 파일명) — 호스트·서명 쿼리는 크롤마다 바뀌므로 제외.
	 *  어드민 커버리지 집계(PipelineStatsService)도 같은 규칙으로 대조해야 해서 public. */
	public static String sourceName(String url) {
		String path = URI.create(url).getPath();
		return path.substring(path.lastIndexOf('/') + 1);
	}
}

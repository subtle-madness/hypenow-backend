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
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 서빙 이미지 아카이브 잡 (태스크 J) — CDN 만료(~4일) 전에 썸네일·프로필을 오브젝트 스토리지로.
 * 대상 선정: 썸네일=image_assets 미기록 shortCode만(1회 불변 — 중복 수집분 다운로드 생략),
 * 프로필=원본 URL 파일명(source_name)이 바뀐 계정만(같은 키 덮어쓰기 — 축적 없음).
 * 실패는 건 단위 격리(미기록 → 다음 실행 재대상), 배치 상한 초과분은 이월(carriedOver).
 *
 * <p>선정과 처리를 분리한다(2026-09-01) — 선정은 읽기전용 트랜잭션 + 커서 fetch 1회 스캔으로
 * 상한 크기 후보만 남기고(전량 리스트 적재는 미러 08-31 OOM과 같은 패턴 — 드라이버 버퍼와
 * Target 리스트가 행 수에 비례해 이중으로 쌓인다), 수 시간짜리 다운로드 루프는 커서·트랜잭션이
 * 닫힌 뒤 raw 커넥션 점유 0으로 돈다. 진행 체크포인트는 image_assets 건별 커밋 자체라
 * 중단돼도 다음 실행 선정이 미기록분을 자연 재대상한다. 키셋 페이지네이션은 불가 —
 * archived 대조가 크로스 DB(analysis↔raw)라 SQL 술어로 못 쓰고, v_contents가 다중 조인 뷰라
 * 페이지마다 조인 스택이 재실행되며, 만료 임박 순(top-N)은 URL 파싱이라 어차피 전 행을 본다.
 */
public class ImageArchiveJob {

	private static final Logger log = LoggerFactory.getLogger(ImageArchiveJob.class);

	static final String KIND_THUMBNAIL = "thumbnail";
	static final String KIND_PROFILE = "profile";
	static final String THUMB_CACHE_CONTROL = "public, max-age=31536000, immutable";
	static final String PROFILE_CACHE_CONTROL = "public, max-age=86400";

	private static final int FETCH_SIZE = 500;

	private final JdbcTemplate raw;
	private final TransactionTemplate rawTx;
	private final JdbcTemplate analysis;
	private final ImageStore store;
	private final ImageDownloader downloader;
	private final AnalyticsSettings settings;
	private final ProgressReporter reporter;

	public ImageArchiveJob(JdbcTemplate rawJdbcTemplate, DataSource analysisDataSource,
			ImageStore store, ImageDownloader downloader, AnalyticsSettings settings,
			ProgressReporter reporter) {
		// 공유 빈의 fetchSize를 건드리지 않도록 잡 전용 사본에만 커서 fetch를 건다(미러 관용구).
		// pgjdbc 커서 모드는 autoCommit=false 전제 — 선정 스캔을 읽기전용 트랜잭션으로 감싼다.
		this.raw = new JdbcTemplate(rawJdbcTemplate.getDataSource());
		this.raw.setFetchSize(FETCH_SIZE);
		this.rawTx = new TransactionTemplate(
				new DataSourceTransactionManager(rawJdbcTemplate.getDataSource()));
		this.rawTx.setReadOnly(true);
		this.analysis = new JdbcTemplate(analysisDataSource);
		this.store = store;
		this.downloader = downloader;
		this.settings = settings;
		this.reporter = reporter;
	}

	public JobResult run() {
		Selection sel = selectTargets();

		int done = 0;
		int failed = 0;
		reporter.report(0, 0, sel.batch().size());
		for (Target t : sel.batch()) {
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
			reporter.report(done, failed, sel.batch().size());
		}
		log.info("이미지 아카이브 완료 — {}건 저장, {}건 실패, 만료 제외 {}건, 무효 제외 {}건{}",
				done, failed, sel.expiredSkipped(), sel.invalidSkipped(),
				sel.carriedOver() ? ", 잔여 " + sel.carriedOverCount() + "건 이월" : "");
		return new JobResult(done, failed, sel.carriedOver());
	}

	private record Selection(List<Target> batch, long survivors, int expiredSkipped,
			int invalidSkipped) {

		boolean carriedOver() {
			return survivors > batch.size();
		}

		long carriedOverCount() {
			return survivors - batch.size();
		}
	}

	/**
	 * 대상 선정 — raw 커넥션·트랜잭션은 이 메서드 안에서만 산다. 커서 스트리밍으로 행마다
	 * 필터(기기록·영구 무효·만료)를 통과시킨 뒤, 만료 임박 순 상위 limit건만 유지하는
	 * 최대 힙(루트=가장 늦은 만료, null=만료 미상은 최후순)으로 메모리를 상한에 고정한다.
	 */
	private Selection selectTargets() {
		Map<String, String> archivedProfiles = new HashMap<>();
		analysis.query("SELECT key, source_name FROM image_assets WHERE kind = 'profile'",
				rs -> {
					archivedProfiles.put(rs.getString(1), rs.getString(2));
				});
		Set<String> archivedThumbs = new HashSet<>(analysis.queryForList(
				"SELECT key FROM image_assets WHERE kind = 'thumbnail'", String.class));

		long nowEpoch = Instant.now().getEpochSecond();
		int limit = settings.archiveBatchLimit();
		Comparator<Entry> byExpiry =
				Comparator.comparing(Entry::oe, Comparator.nullsLast(Comparator.naturalOrder()));
		PriorityQueue<Entry> keep = new PriorityQueue<>(Math.max(1, limit), byExpiry.reversed());
		long[] survivors = {0};
		int[] expiredSkipped = {0};
		int[] invalidSkipped = {0};

		java.util.function.Consumer<Target> consider = t -> {
			if (permanentlyInvalid(t.url())) {
				invalidSkipped[0]++;
				return;
			}
			Long oe = expiryEpoch(t.url());
			if (oe != null && oe <= nowEpoch) {
				expiredSkipped[0]++;
				return;
			}
			survivors[0]++;
			keep.add(new Entry(t, oe));
			if (keep.size() > limit) {
				keep.poll();
			}
		};

		rawTx.executeWithoutResult(tx -> {
			raw.query("""
					SELECT handle, profile_image_url FROM v_accounts
					WHERE profile_image_url IS NOT NULL
					""", rs -> {
				Target t = new Target(KIND_PROFILE, rs.getString(1), rs.getString(2));
				if (profileChanged(t, archivedProfiles.get(t.key()))) {
					consider.accept(t);
				}
			});
			raw.query("""
					SELECT short_code, thumbnail_url FROM v_contents
					WHERE thumbnail_url IS NOT NULL
					""", rs -> {
				Target t = new Target(KIND_THUMBNAIL, rs.getString(1), rs.getString(2));
				if (!archivedThumbs.contains(t.key())) {
					consider.accept(t);
				}
			});
		});

		List<Entry> ordered = new ArrayList<>(keep);
		ordered.sort(byExpiry);
		List<Target> batch = ordered.stream().map(Entry::target).toList();
		return new Selection(batch, survivors[0], expiredSkipped[0], invalidSkipped[0]);
	}

	private record Entry(Target target, Long oe) {
	}

	record Target(String kind, String key, String url) {

		String objectPath() {
			return (KIND_PROFILE.equals(kind) ? "profile/" : "thumb/") + key + ".jpg";
		}
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

package com.celfit.was.v1.perfdashboard;

import com.celfit.was.archive.ArchiveWriter;
import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandPostCampaignRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.monitoring.MonitoringReadRepository;
import com.celfit.was.v1.brandmonitoring.BrandPostAssembler;
import com.celfit.was.v1.monitoring.TrackingItemAssembler;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * 성과 대시보드 고정 지연(FE 실측 ~7초) 원인 규명용 1회성 하니스 — CI에서는 돌지 않는다
 * (PERF_DIAG=1 환경변수 필요). 운영 덤프를 복원한 로컬 컨테이너(5434)에 실제 조립 코드를
 * 그대로 배선해 구간별 소요를 잰다. 진단이 끝나면 삭제 대상.
 */
@EnabledIfEnvironmentVariable(named = "PERF_DIAG", matches = "1")
class PerfDiagnosisHarnessTest {

	private static final long USER_ID = 4L;

	private static JdbcClient jdbc(String db) {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl("jdbc:postgresql://localhost:5434/" + db);
		config.setUsername("postgres");
		config.setPassword("postgres");
		config.setMaximumPoolSize(3);
		return JdbcClient.create(new HikariDataSource(config));
	}

	@Test
	void 구간별_소요를_잰다() {
		JdbcClient app = jdbc("analysis");
		JdbcClient mon = jdbc("monitoring");

		ArchiveWriter archiveWriter = new ArchiveWriter(app);
		MonitoringItemRepository itemRepository = new MonitoringItemRepository(app, archiveWriter);
		CampaignRepository campaignRepository = new CampaignRepository(app, archiveWriter);
		MonitoringReadRepository readRepository = new MonitoringReadRepository(mon);
		BrandReadRepository brandReadRepository = new BrandReadRepository(mon);
		BrandDirectPostRepository directPostRepository = new BrandDirectPostRepository(app);
		BrandPostCampaignRepository postCampaignRepository = new BrandPostCampaignRepository(app);
		BrandLinkRepository linkRepository = new BrandLinkRepository(app);
		TrackingItemAssembler trackingItemAssembler = new TrackingItemAssembler(itemRepository,
				campaignRepository, Optional.of(readRepository), new ObjectMapper());
		BrandPostAssembler brandPostAssembler = new BrandPostAssembler(brandReadRepository,
				postCampaignRepository, directPostRepository, trackingItemAssembler, itemRepository, false);
		PerformanceContentAssembler assembler = new PerformanceContentAssembler(trackingItemAssembler,
				linkRepository, campaignRepository, Optional.of(brandReadRepository),
				Optional.of(brandPostAssembler));

		// 워밍업 1회(JIT·풀 초기화) 후 본측정 — 운영은 항상 워밍업된 상태이므로 본측정이 비교 대상.
		time("[워밍업] assemble 전체", () -> assembler.assemble(USER_ID));

		for (int run = 1; run <= 2; run++) {
			System.out.println("===== 본측정 " + run + " =====");
			time("assemble 전체(댓글 포함 — 종전 목록 경로)", () -> assembler.assemble(USER_ID));
			time("assembleSlim(댓글 없음 — 신규 목록·비교 경로)", () -> assembler.assembleSlim(USER_ID));
		}

		System.out.println("===== 구간별(assemble 내부 순서 그대로) =====");
		var legacy = time("1. 레거시 assembleList", () -> trackingItemAssembler.assembleList(USER_ID));
		System.out.println("   레거시 아이템 수 = " + legacy.items().size());
		var direct = time("2. directPostRepository.findByUser", () -> directPostRepository.findByUser(USER_ID));
		List<BrandLinkRow> links = time("3. linkRepository.findAllActiveByUser",
				() -> linkRepository.findAllActiveByUser(USER_ID));
		System.out.println("   direct 매핑 = " + direct.size() + "건, 링크 = " + links.size() + "개");

		System.out.println("===== 브랜드별 tagged 조립(loadTagged 내부) =====");
		for (BrandLinkRow link : links) {
			Optional<BrandAccountRow> account = time("  findAccount(" + link.brandId() + ")",
					() -> brandReadRepository.findAccount(link.brandId()));
			if (account.isEmpty()) {
				continue;
			}
			var posts = time("  assembleBrandPosts(" + link.brandId() + ") [5쿼리+매핑]",
					// 진단 하니스는 조회 비용의 상한을 보려는 것이라 전량(ALL)으로 잰다.
					() -> brandPostAssembler.assembleBrandPosts(USER_ID, account.get(), true,
							com.celfit.was.v1.brandmonitoring.BrandPostAssembler.BrandPostScope.ALL, true,
							link.accountType()));
			System.out.println("    게시물 = " + posts.size() + "건");
		}

		System.out.println("===== assembleBrandPosts 내부 쿼리별(최대 브랜드 1개로 분해) =====");
		// BrandPostAssembler.windowCutoff()와 같은 식(패키지 밖이라 재계산) — KST 오늘−365일 자정.
		var cutoff = java.time.LocalDate.now(com.celfit.was.v1.common.KstTimestamps.KST).minusDays(365)
				.atStartOfDay(com.celfit.was.v1.common.KstTimestamps.KST).toOffsetDateTime();
		BrandLinkRow biggest = links.get(0);
		long biggestCount = -1;
		for (BrandLinkRow link : links) {
			long count = brandReadRepository.findBrandPostsInWindow(link.brandId(), cutoff, false).size();
			if (count > biggestCount) {
				biggestCount = count;
				biggest = link;
			}
		}
		long brandId = biggest.brandId();
		var posts = time("  findBrandPostsInWindow(" + brandId + ")",
				() -> brandReadRepository.findBrandPostsInWindow(brandId, cutoff, false));
		Set<String> codes = posts.stream().map(BrandReadRepository.BrandTaggedPostRow::shortCode)
				.collect(Collectors.toSet());
		System.out.println("    게시물 = " + codes.size() + "건");
		var meta = time("  findPostMeta", () -> brandReadRepository.findPostMeta(codes));
		System.out.println("    meta 행 = " + meta.size());
		var snapshots = time("  findSnapshots", () -> brandReadRepository.findSnapshots(codes));
		System.out.println("    snapshot 행 = " + snapshots.size());
		var comments = time("  findComments(45캡)", () -> brandReadRepository.findComments(codes, 45));
		System.out.println("    comment 행 = " + comments.size());
		Set<String> igUserIds = posts.stream().map(BrandReadRepository.BrandTaggedPostRow::authorIgUserId)
				.filter(java.util.Objects::nonNull).collect(Collectors.toSet());
		var authors = time("  findAuthors", () -> brandReadRepository.findAuthors(igUserIds));
		System.out.println("    author 행 = " + authors.size());
	}

	private static <T> T time(String label, java.util.function.Supplier<T> body) {
		long start = System.nanoTime();
		T result = body.get();
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;
		System.out.println(label + " = " + elapsedMs + "ms");
		return result;
	}
}

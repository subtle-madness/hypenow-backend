package com.celfit.was.v1.brandmonitoring;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandPostCampaignRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.v1.common.ApiResponse;
import com.celfit.was.v1.monitoring.TrackingItemAssembler;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 성능 분해 벤치마크(수동 실행 전용) — 운영 계정 119급(브랜드 풀 4,995건·댓글 54k·스냅샷 10k)
 * 합성 데이터로 GET /v1/brand-monitoring/accounts/{id}/posts 경로의 단계별 소요 시간을 실측한다.
 * 2026-08-25 9초 지연 분석에서 병목 분해에 쓴 하니스 — 개선 작업의 전후 비교용으로 보존한다.
 *
 * <p>실행 방법: 로컬 실데이터 postgres 컨테이너(포트 5433)에 시드 적용 후 {@code @Disabled}를
 * 잠시 떼고 단건 실행한다. CI에는 로컬 DB가 없어 항상 비활성이다.
 *
 * <pre>
 * docker exec -i &lt;PG_CONTAINER&gt; psql -U crawler -d postgres -c "CREATE DATABASE perf119"
 * docker exec -i &lt;PG_CONTAINER&gt; psql -U crawler -d perf119 &lt; was/src/test/resources/brandmonitoring/perf119-seed.sql
 * ./gradlew :was:test --tests "com.celfit.was.v1.brandmonitoring.BrandPostAssemblyBenchmarkTest" --rerun-tasks --info
 * </pre>
 *
 * <p>2026-08-25 실측(M계열 Mac, 워밍업 후): 조회+매핑 ~1.2초(댓글 54k행 650ms가 최대 지분),
 * 조립 ~0.2초, 직렬화 13ms(JSON 10MB), gzip 117ms — 합계 ~1.6초. 운영 A1 코어(단일 스레드
 * ~3-4배 느림) 환산 시 5~7초로, 운영 실측 6.4초+ 하한과 일치.
 */
@Disabled("수동 실행 전용 — 로컬 perf119 DB 시딩 필요(클래스 주석 참조)")
class BrandPostAssemblyBenchmarkTest {

	@Test
	void benchmark() throws Exception {
		DriverManagerDataSource ds = new DriverManagerDataSource(
				"jdbc:postgresql://localhost:5433/perf119", "crawler", "crawler");
		JdbcClient jdbc = JdbcClient.create(ds);
		BrandReadRepository repo = new BrandReadRepository(jdbc);

		BrandDirectPostRepository directRepo = mock(BrandDirectPostRepository.class);
		when(directRepo.findPendingByUser(anyLong())).thenReturn(List.of());
		when(directRepo.shortCodesByUser(anyLong())).thenReturn(Set.of());
		when(directRepo.findCampaignLinkedShortCodes(anyLong())).thenReturn(List.of());
		BrandPostCampaignRepository campaignRepo = mock(BrandPostCampaignRepository.class);
		when(campaignRepo.findByBrandAndShortCodes(anyLong(), org.mockito.ArgumentMatchers.any()))
				.thenReturn(List.of());
		TrackingItemAssembler trackingAssembler = mock(TrackingItemAssembler.class);
		MonitoringItemRepository itemRepo = mock(MonitoringItemRepository.class);

		BrandPostAssembler assembler = new BrandPostAssembler(repo, campaignRepo, directRepo,
				trackingAssembler, itemRepo, false);

		tools.jackson.databind.ObjectMapper om = new tools.jackson.databind.ObjectMapper();

		for (int iter = 1; iter <= 3; iter++) {
			System.out.println("=== iteration " + iter + " ===");
			long t0 = System.nanoTime();
			var account = repo.findAccount(119).orElseThrow();
			mark("findAccount", t0);

			// 단계별: 배치 조회 각각 (query+mapping)
			long t = System.nanoTime();
			var posts = repo.findBrandPostsInWindow(119, BrandPostAssembler.windowCutoff(), true);
			mark("findBrandPostsInWindow rows=" + posts.size(), t);

			Set<String> codes = new java.util.LinkedHashSet<>();
			posts.forEach(p -> codes.add(p.shortCode()));

			t = System.nanoTime();
			var meta = repo.findPostMeta(codes);
			mark("findPostMeta rows=" + meta.size(), t);

			t = System.nanoTime();
			var snaps = repo.findSnapshots(codes);
			mark("findSnapshots rows=" + snaps.size(), t);

			t = System.nanoTime();
			var comments = repo.findComments(codes, 45);
			mark("findComments rows=" + comments.size(), t);

			t = System.nanoTime();
			Set<String> igIds = new java.util.LinkedHashSet<>();
			posts.forEach(p -> { if (p.authorIgUserId() != null) igIds.add(p.authorIgUserId()); });
			var authors = repo.findAuthors(igIds);
			mark("findAuthors rows=" + authors.size(), t);

			// 전체 조립(위 조회 포함 재실행 — 컨트롤러가 실제 밟는 경로)
			t = System.nanoTime();
			List<BrandPostResponse> all = assembler.assembleForBrand(65, account, "own");
			mark("assembleForBrand total posts=" + all.size(), t);

			// 컨트롤러 잔여 로직: 창 필터 + 정렬 + limit + meta
			t = System.nanoTime();
			LocalDate windowStart = LocalDate.now().minusMonths(12);
			List<BrandPostResponse> windowed = all.stream()
					.filter(p -> {
						LocalDate d = BrandPostAssembler.uploadedOn(p);
						return "direct".equals(p.source()) || (d != null && !d.isBefore(windowStart));
					})
					.toList();
			List<BrandPostResponse> filtered = windowed.stream()
					.sorted(Comparator.comparing(BrandPostAssembler::uploadedOn,
							Comparator.nullsLast(Comparator.reverseOrder()))
							.thenComparing(BrandPostResponse::shortcode))
					.limit(2000)
					.toList();
			Map<String, Object> metaMap = new LinkedHashMap<>();
			metaMap.put("total", filtered.size());
			mark("controller filter+sort+limit -> " + filtered.size(), t);

			// 직렬화
			t = System.nanoTime();
			byte[] json = om.writeValueAsBytes(ApiResponse.ok(filtered, metaMap));
			mark("jackson serialize bytes=" + json.length, t);

			// gzip (톰캣 압축 등가)
			t = System.nanoTime();
			ByteArrayOutputStream bos = new ByteArrayOutputStream(json.length / 4);
			try (GZIPOutputStream gz = new GZIPOutputStream(bos, 8192)) {
				gz.write(json);
			}
			mark("gzip -> bytes=" + bos.size(), t);

			mark("TOTAL", t0);
		}
	}

	private static void mark(String label, long startNanos) {
		System.out.printf("%-45s %8.1f ms%n", label, (System.nanoTime() - startNanos) / 1e6);
	}
}

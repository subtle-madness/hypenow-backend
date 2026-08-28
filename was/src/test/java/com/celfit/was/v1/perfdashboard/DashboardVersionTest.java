package com.celfit.was.v1.perfdashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.MonitoringReadRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

/**
 * 버전키 계산기 단위 테스트(08-13 설계 §6 "단위" 층) — 입력이 <b>각각 단독으로</b> 키를 바꾸는지가
 * 이 테스트의 전부다. 테이블 주도 케이스는 <b>10항목</b>이다(설계 §2-1의 입력 종류를 실제로 흔들 수
 * 있는 단위까지 편 것 — ① 레거시 스윕 1 + ② 브랜드 계정 행 필드 4 + ④ 유저 쓰기 지문 5).
 * ⑤ KST 날짜와 ⑥ 배포 세대는 시계·{@code BuildProperties}를 갈아야 해 각자 별도 테스트다. ETag가 입력 하나를 놓치면 그건 실패가 아니라 <b>낡은 데이터의 조용한 서빙</b>이라
 * 여기서 고정한다(설계 §2-1).
 *
 * <p>지문 SQL 자체는 여기서 검증하지 않는다(리포지토리 mock) — SQL은
 * {@code DashboardVersionRepositoryTest}(Testcontainers)가 맡는다.
 */
class DashboardVersionTest {

	private static final long USER_ID = 7L;

	// ---------- 픽스처 ----------

	/**
	 * 버전키 입력 전부 + 시계 + monitoring 활성 여부를 모아 둔 가변 픽스처 — 테이블 주도 테스트가
	 * 기준값에서 하나씩만 흔든다. 원문자는 설계 §2-1의 입력 번호다(③ 이미지 아카이브 워터마크는
	 * 의도적으로 뺐다 — {@code DashboardVersion} javadoc "수용된 지연" ①).
	 */
	private static final class Inputs {

		// ① 레거시 스윕 워터마크
		OffsetDateTime legacySweepAt = odt("2026-08-28T03:00:00Z");
		// ② 브랜드 스윕 워터마크(brand_account.last_swept_at)
		OffsetDateTime brandLastSweptAt = odt("2026-08-28T04:00:00Z");
		// ②' 브랜드 커버리지 상한(brand_account.covered_until) — 클램프 술어의 입력이라 응답을 바꾼다
		OffsetDateTime brandCoveredUntil = odt("2025-09-01T00:00:00Z");
		// ②'' 스윕 밖 경로(창 확장·재활성화)로 바뀌는 계정 행 필드 — /comparison의 covered 판정·창·응답
		// 필드 산지다. 브랜드가 유저 간 공유 자산이라 남의 등록·확장이 내 응답을 바꾼다.
		OffsetDateTime brandBackfillCompletedAt = odt("2026-07-05T00:00:00Z");
		int brandCollectionMonths = 12;
		// ④ 유저 쓰기 지문 5종
		String itemsFingerprint = "0000000000000000000000000000aaaa";
		String linksFingerprint = "0000000000000000000000000000bbbb";
		String directFingerprint = "0000000000000000000000000000cccc";
		String campaignsFingerprint = "0000000000000000000000000000dddd";
		String postCampaignsFingerprint = "0000000000000000000000000000eeee";
		// ⑤ KST 날짜
		Instant now = Instant.parse("2026-08-28T05:00:00Z");   // KST 2026-08-28 14:00
		boolean monitoringEnabled = true;
		// ⑥ 배포 세대(설계 §2-6) — ⑤와 별개 축이다. 기본은 BuildProperties 부재("dev" 폴백, 로컬·테스트)
		ObjectProvider<BuildProperties> buildProperties = buildPropertiesProvider(null);
	}

	private static String compute(Inputs in) {
		DashboardVersionRepository repository = mock(DashboardVersionRepository.class);
		given(repository.monitoringItemsFingerprint(USER_ID)).willReturn(in.itemsFingerprint);
		given(repository.brandLinksFingerprint(USER_ID)).willReturn(in.linksFingerprint);
		given(repository.directPostsFingerprint(USER_ID)).willReturn(in.directFingerprint);
		given(repository.campaignsFingerprint(USER_ID)).willReturn(in.campaignsFingerprint);
		given(repository.postCampaignLinksFingerprint(USER_ID)).willReturn(in.postCampaignsFingerprint);

		BrandLinkRepository linkRepository = mock(BrandLinkRepository.class);
		Optional<MonitoringReadRepository> monitoringReadRepository;
		Optional<BrandReadRepository> brandReadRepository;
		if (in.monitoringEnabled) {
			MonitoringReadRepository monitoringRead = mock(MonitoringReadRepository.class);
			given(monitoringRead.lastSuccessfulSweepAt()).willReturn(in.legacySweepAt);
			monitoringReadRepository = Optional.of(monitoringRead);

			BrandReadRepository brandRead = mock(BrandReadRepository.class);
			// 브랜드 2건 — 연결 순서는 뒤죽박죽으로 주고(2 → 1) 정렬이 실제로 걸리는지 본다.
			given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of(link(2L), link(1L)));
			given(brandRead.findAccount(1L)).willReturn(Optional.of(
					account(1L, in.brandLastSweptAt, in.brandCoveredUntil, in.brandBackfillCompletedAt,
							in.brandCollectionMonths)));
			given(brandRead.findAccount(2L)).willReturn(Optional.of(
					account(2L, odt("2026-08-28T04:30:00Z"), odt("2025-10-01T00:00:00Z"),
							odt("2026-07-06T00:00:00Z"), 6)));
			brandReadRepository = Optional.of(brandRead);
		} else {
			given(linkRepository.findAllActiveByUser(USER_ID)).willReturn(List.of());
			monitoringReadRepository = Optional.empty();
			brandReadRepository = Optional.empty();
		}

		DashboardVersion version = new DashboardVersion(repository, monitoringReadRepository, linkRepository,
				brandReadRepository, in.buildProperties, Clock.fixed(in.now, ZoneOffset.UTC));
		return version.compute(USER_ID);
	}

	private static String computeBaseline() {
		return compute(new Inputs());
	}

	private static String compute(Consumer<Inputs> mutation) {
		Inputs in = new Inputs();
		mutation.accept(in);
		return compute(in);
	}

	// ---------- 입력 판별 ----------

	@Test
	void 입력이_하나라도_바뀌면_키가_바뀐다() {
		Map<String, Consumer<Inputs>> mutations = new LinkedHashMap<>();
		mutations.put("① 레거시 스윕", in -> in.legacySweepAt = odt("2026-08-28T03:00:01Z"));
		mutations.put("② 브랜드 last_swept_at", in -> in.brandLastSweptAt = odt("2026-08-28T04:00:01Z"));
		mutations.put("② 브랜드 covered_until", in -> in.brandCoveredUntil = odt("2025-09-02T00:00:00Z"));
		// 스윕 밖 경로로 바뀌는 필드 — last_swept_at·covered_until이 미동인 채로 응답만 달라진다.
		mutations.put("② 브랜드 backfill_completed_at",
				in -> in.brandBackfillCompletedAt = odt("2026-07-05T00:00:01Z"));
		mutations.put("② 브랜드 collection_months", in -> in.brandCollectionMonths = 6);
		mutations.put("④ 아이템 지문", in -> in.itemsFingerprint = "0000000000000000000000000000a0a0");
		mutations.put("④ 링크 지문", in -> in.linksFingerprint = "0000000000000000000000000000b0b0");
		mutations.put("④ direct 지문", in -> in.directFingerprint = "0000000000000000000000000000c0c0");
		mutations.put("④ 캠페인 지문", in -> in.campaignsFingerprint = "0000000000000000000000000000d0d0");
		mutations.put("④ 부착 지문", in -> in.postCampaignsFingerprint = "0000000000000000000000000000e0e0");

		String baseline = computeBaseline();
		Map<String, String> keys = new LinkedHashMap<>();
		for (Map.Entry<String, Consumer<Inputs>> entry : mutations.entrySet()) {
			String key = compute(entry.getValue());
			assertThat(key).as("%s 을(를) 바꿨는데 키가 그대로다 — 지문이 이 입력을 놓쳤다", entry.getKey())
					.isNotEqualTo(baseline);
			keys.put(entry.getKey(), key);
		}
		// 서로 다른 입력이 같은 자리에 접혀 구분이 사라지지 않는지 — 8종의 키가 전부 달라야 한다.
		assertThat(new ArrayList<>(keys.values())).doesNotHaveDuplicates();
	}

	@Test
	void 유저가_다르면_키가_다르다() {
		Inputs in = new Inputs();
		DashboardVersionRepository repository = mock(DashboardVersionRepository.class);
		given(repository.monitoringItemsFingerprint(org.mockito.ArgumentMatchers.anyLong()))
				.willReturn(in.itemsFingerprint);
		given(repository.brandLinksFingerprint(org.mockito.ArgumentMatchers.anyLong()))
				.willReturn(in.linksFingerprint);
		given(repository.directPostsFingerprint(org.mockito.ArgumentMatchers.anyLong()))
				.willReturn(in.directFingerprint);
		given(repository.campaignsFingerprint(org.mockito.ArgumentMatchers.anyLong()))
				.willReturn(in.campaignsFingerprint);
		given(repository.postCampaignLinksFingerprint(org.mockito.ArgumentMatchers.anyLong()))
				.willReturn(in.postCampaignsFingerprint);
		BrandLinkRepository linkRepository = mock(BrandLinkRepository.class);
		given(linkRepository.findAllActiveByUser(org.mockito.ArgumentMatchers.anyLong())).willReturn(List.of());

		DashboardVersion version = new DashboardVersion(repository, Optional.empty(), linkRepository,
				Optional.empty(), in.buildProperties, Clock.fixed(in.now, ZoneOffset.UTC));
		assertThat(version.compute(7L)).isNotEqualTo(version.compute(8L));
	}

	@Test
	void 같은_입력이면_키가_같다() {
		assertThat(computeBaseline()).isEqualTo(computeBaseline());
		assertThat(computeBaseline()).hasSize(32).matches("[0-9a-f]{32}");
	}

	@Test
	void KST_자정을_넘기면_키가_바뀐다() {
		// 데이터가 하나도 안 바뀌어도 KST 날짜가 넘어가면 파생값(상태·365일 창)이 달라진다(설계 §2-1 ⑤).
		String before = compute(in -> in.now = Instant.parse("2026-08-28T14:59:59Z"));   // KST 08-28 23:59:59
		String after = compute(in -> in.now = Instant.parse("2026-08-28T15:00:00Z"));    // KST 08-29 00:00:00
		assertThat(after).isNotEqualTo(before);

		// 같은 KST 날짜 안에서의 시각 변화는 키를 흔들지 않는다(시간 버킷 방식 기각 — 설계 §2-4).
		String sameDay = compute(in -> in.now = Instant.parse("2026-08-28T05:30:00Z"));
		assertThat(sameDay).isEqualTo(computeBaseline());
	}

	@Test
	void monitoring_비활성이면_레거시_브랜드_입력이_상수로_접히고_키는_안정적이다() {
		String off = compute(in -> in.monitoringEnabled = false);
		assertThat(off).isEqualTo(compute(in -> in.monitoringEnabled = false));
		// 비활성 키는 워터마크가 상수로 접힌 값이라 활성 키와 달라야 한다(같으면 접기가 값을 먹은 것).
		assertThat(off).isNotEqualTo(computeBaseline());
		// 비활성이어도 유저 쓰기 지문은 그대로 판별한다 — app 스키마는 monitoring 게이트 밖이다.
		assertThat(compute(in -> {
			in.monitoringEnabled = false;
			in.itemsFingerprint = "0000000000000000000000000000a0a0";
		})).isNotEqualTo(off);
	}

	@Test
	void 배포_세대가_다르면_키가_바뀐다() {
		// 설계 §2-6 — 응답 스키마가 바뀐 배포에서 옛 ETag가 그대로 맞으면 새 필드가 영영 안 나간다.
		String dev = computeBaseline();   // BuildProperties 부재 → "dev"
		String build1 = compute(in -> in.buildProperties =
				buildPropertiesProvider(Instant.parse("2026-08-28T00:00:00Z")));
		String build2 = compute(in -> in.buildProperties =
				buildPropertiesProvider(Instant.parse("2026-08-29T00:00:00Z")));

		assertThat(build1).isNotEqualTo(dev);
		assertThat(build2).isNotEqualTo(build1);
		// 같은 빌드 시각이면 같은 세대다(재기동만으로 전 유저 ETag가 날아가지 않는다).
		assertThat(build1).isEqualTo(compute(in -> in.buildProperties =
				buildPropertiesProvider(Instant.parse("2026-08-28T00:00:00Z"))));
	}

	// ---------- ETag 표면 ----------

	@Test
	void etagOf는_약한_검증자_형식이다() {
		String version = computeBaseline();
		assertThat(DashboardVersion.etagOf(version)).isEqualTo("W/\"" + version.substring(0, 16) + "\"");
		assertThat(DashboardVersion.etagOf(version)).matches("W/\"[0-9a-f]{16}\"");
	}

	@Test
	void matches는_W_접두와_복수_값과_별표를_처리한다() {
		String etag = "W/\"abc\"";
		assertThat(DashboardVersion.matches("W/\"abc\"", etag)).isTrue();
		assertThat(DashboardVersion.matches("\"abc\"", etag)).isTrue();              // 강한 형태로 와도 의미는 같다
		assertThat(DashboardVersion.matches("\"zzz\", \"abc\"", etag)).isTrue();     // 복수 값 중 하나 일치
		assertThat(DashboardVersion.matches("W/\"zzz\", W/\"abc\"", etag)).isTrue();
		assertThat(DashboardVersion.matches("*", etag)).isTrue();
		assertThat(DashboardVersion.matches("\"zzz\"", etag)).isFalse();
		assertThat(DashboardVersion.matches("\"zzz\", \"yyy\"", etag)).isFalse();
		assertThat(DashboardVersion.matches(null, etag)).isFalse();
		assertThat(DashboardVersion.matches("", etag)).isFalse();
		assertThat(DashboardVersion.matches("  ", etag)).isFalse();
	}

	// ---------- 헬퍼 ----------

	private static OffsetDateTime odt(String instant) {
		return Instant.parse(instant).atOffset(ZoneOffset.UTC);
	}

	private static BrandLinkRow link(long brandId) {
		return new BrandLinkRow(brandId, USER_ID, brandId, "brand" + brandId, "own", 12,
				odt("2026-08-01T00:00:00Z"), null);
	}

	private static BrandAccountRow account(long id, OffsetDateTime lastSweptAt, OffsetDateTime coveredUntil,
			OffsetDateTime backfillCompletedAt, int collectionMonths) {
		return new BrandAccountRow(id, "brand" + id, LocalDate.of(2026, 8, 28), lastSweptAt,
				odt("2026-07-01T00:00:00Z"), backfillCompletedAt, null,
				100L, 10L, 20L, null, null, null, null, null, "ACTIVE", null, collectionMonths,
				odt("2026-07-01T00:00:00Z"), false, coveredUntil);
	}

	/** {@code null}이면 {@code BuildProperties} 부재(로컬·테스트 — {@code "dev"} 폴백)를 흉내 낸다. */
	@SuppressWarnings("unchecked")
	private static ObjectProvider<BuildProperties> buildPropertiesProvider(Instant buildTime) {
		ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
		if (buildTime == null) {
			given(provider.getIfAvailable()).willReturn(null);
		} else {
			Properties properties = new Properties();
			properties.setProperty("time", Long.toString(buildTime.toEpochMilli()));
			given(provider.getIfAvailable()).willReturn(new BuildProperties(properties));
		}
		return provider;
	}
}

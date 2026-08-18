package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandDirectPostRepository.PendingMigrationRow;
import com.celfit.was.monitoring.BrandPostCampaignRepository;
import com.celfit.was.monitoring.MonitoringApiException;
import com.celfit.was.monitoring.MonitoringCommandClient;
import com.celfit.was.monitoring.MonitoringUnavailableException;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * direct 게시물 이관 잡 단위 고정(2026-08-18 direct 통합 §M2) — 브랜드별 shortcode dedupe,
 * 404/422 확정 정산, 503·예기치 못한 오류 건너뜀, 캠페인 연결 이관을 mock으로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class BrandDirectPostMigrationJobTest {

	@Mock
	private BrandDirectPostRepository directPostRepository;
	@Mock
	private BrandPostCampaignRepository postCampaignRepository;
	@Mock
	private MonitoringCommandClient commandClient;

	private BrandDirectPostMigrationJob job() {
		return new BrandDirectPostMigrationJob(directPostRepository, postCampaignRepository, commandClient);
	}

	@Test
	void 대상이_없으면_전부_0건이다() {
		given(directPostRepository.findAllPending()).willReturn(List.of());

		var result = job().run();

		assertThat(result.targets()).isZero();
		assertThat(result.migrated()).isZero();
		then(commandClient).should(never()).registerDirectPost(anyLong(), any(), any(), anyBoolean());
	}

	@Test
	void 성공하면_migrated_at을_찍고_캠페인이_있으면_연결한다() {
		var row = new PendingMigrationRow(7L, 100L, "ABC", OffsetDateTime.parse("2026-08-01T00:00:00Z"), 55L);
		given(directPostRepository.findAllPending()).willReturn(List.of(row));

		var result = job().run();

		assertThat(result.migrated()).isEqualTo(1);
		assertThat(result.targets()).isEqualTo(1);
		then(commandClient).should().registerDirectPost(100L, "ABC", row.createdAt(), true);
		then(directPostRepository).should().markMigrated(100L, "ABC");
		then(postCampaignRepository).should().upsert(100L, "ABC", 55L, 7L);
	}

	@Test
	void 캠페인이_없으면_brand_post_campaigns를_건드리지_않는다() {
		var row = new PendingMigrationRow(7L, 100L, "ABC", OffsetDateTime.parse("2026-08-01T00:00:00Z"), null);
		given(directPostRepository.findAllPending()).willReturn(List.of(row));

		job().run();

		then(postCampaignRepository).should(never()).upsert(anyLong(), any(), anyLong(), anyLong());
	}

	/** 같은 (brandId, shortCode)를 여러 유저가 등록했으면 monitoring 호출은 1회만 — 가장 이른 created_at을 쓴다. */
	@Test
	void 같은_게시물의_여러_유저_등록은_한_번만_이관되고_가장_이른_시각을_쓴다() {
		var later = new PendingMigrationRow(7L, 100L, "ABC", OffsetDateTime.parse("2026-08-05T00:00:00Z"), null);
		var earlier = new PendingMigrationRow(8L, 100L, "ABC", OffsetDateTime.parse("2026-08-01T00:00:00Z"), 55L);
		given(directPostRepository.findAllPending()).willReturn(List.of(later, earlier));

		var result = job().run();

		assertThat(result.targets()).isEqualTo(1);
		assertThat(result.migrated()).isEqualTo(1);
		then(commandClient).should(times(1)).registerDirectPost(eq(100L), eq("ABC"),
				eq(OffsetDateTime.parse("2026-08-01T00:00:00Z")), eq(true));
		// dedupe는 monitoring 호출에만 적용된다 — 캠페인 연결은 원 매핑 각각(유저별)이 개별 행으로 남는다.
		then(postCampaignRepository).should().upsert(100L, "ABC", 55L, 8L);
		then(directPostRepository).should(times(1)).markMigrated(100L, "ABC");
	}

	@Test
	void POST_NOT_FOUND_404는_migrated_at을_찍고_확정실패로_집계한다() {
		var row = new PendingMigrationRow(7L, 100L, "ABC", OffsetDateTime.parse("2026-08-01T00:00:00Z"), null);
		given(directPostRepository.findAllPending()).willReturn(List.of(row));
		willThrow(new MonitoringApiException("POST_NOT_FOUND", "게시물을 찾을 수 없습니다.", 404))
				.given(commandClient).registerDirectPost(100L, "ABC", row.createdAt(), true);

		var result = job().run();

		assertThat(result.permanentlyFailed()).isEqualTo(1);
		assertThat(result.migrated()).isZero();
		then(directPostRepository).should().markMigrated(100L, "ABC");
		then(postCampaignRepository).should(never()).upsert(anyLong(), any(), anyLong(), anyLong());
	}

	@Test
	void PRIVATE_ACCOUNT_422도_migrated_at을_찍고_확정실패로_집계한다() {
		var row = new PendingMigrationRow(7L, 100L, "ABC", OffsetDateTime.parse("2026-08-01T00:00:00Z"), null);
		given(directPostRepository.findAllPending()).willReturn(List.of(row));
		willThrow(new MonitoringApiException("PRIVATE_ACCOUNT", "비공개 계정입니다.", 422))
				.given(commandClient).registerDirectPost(100L, "ABC", row.createdAt(), true);

		var result = job().run();

		assertThat(result.permanentlyFailed()).isEqualTo(1);
		then(directPostRepository).should().markMigrated(100L, "ABC");
	}

	@Test
	void 모니터링_불능_503은_건너뛰고_migrated_at을_찍지_않는다() {
		var row = new PendingMigrationRow(7L, 100L, "ABC", OffsetDateTime.parse("2026-08-01T00:00:00Z"), null);
		given(directPostRepository.findAllPending()).willReturn(List.of(row));
		willThrow(new MonitoringUnavailableException("연결 실패", null))
				.given(commandClient).registerDirectPost(100L, "ABC", row.createdAt(), true);

		var result = job().run();

		assertThat(result.skipped()).isEqualTo(1);
		assertThat(result.migrated()).isZero();
		assertThat(result.permanentlyFailed()).isZero();
		then(directPostRepository).should(never()).markMigrated(anyLong(), any());
	}

	@Test
	void 예기치_못한_API_오류는_건너뛰고_migrated_at을_찍지_않는다() {
		var row = new PendingMigrationRow(7L, 100L, "ABC", OffsetDateTime.parse("2026-08-01T00:00:00Z"), null);
		given(directPostRepository.findAllPending()).willReturn(List.of(row));
		willThrow(new MonitoringApiException("INTERNAL_ERROR", "알 수 없는 오류", 500))
				.given(commandClient).registerDirectPost(100L, "ABC", row.createdAt(), true);

		var result = job().run();

		assertThat(result.skipped()).isEqualTo(1);
		then(directPostRepository).should(never()).markMigrated(anyLong(), any());
	}

	@Test
	void 여러_브랜드_게시물이_섞여도_각각_독립적으로_정산된다() {
		var ok = new PendingMigrationRow(7L, 100L, "ABC", OffsetDateTime.parse("2026-08-01T00:00:00Z"), null);
		var notFound = new PendingMigrationRow(7L, 200L, "XYZ", OffsetDateTime.parse("2026-08-01T00:00:00Z"), null);
		given(directPostRepository.findAllPending()).willReturn(List.of(ok, notFound));
		given(commandClient.registerDirectPost(100L, "ABC", ok.createdAt(), true)).willReturn(
				new MonitoringCommandClient.DirectPostResult("ABC", "creator", java.time.Instant.now(), "REELS"));
		willThrow(new MonitoringApiException("POST_NOT_FOUND", "없음", 404))
				.given(commandClient).registerDirectPost(200L, "XYZ", notFound.createdAt(), true);

		var result = job().run();

		assertThat(result.targets()).isEqualTo(2);
		assertThat(result.migrated()).isEqualTo(1);
		assertThat(result.permanentlyFailed()).isEqualTo(1);
		then(directPostRepository).should().markMigrated(100L, "ABC");
		then(directPostRepository).should().markMigrated(200L, "XYZ");
	}
}

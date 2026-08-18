package com.celfit.was.v1.brandmonitoring;

import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandDirectPostRepository.PendingMigrationRow;
import com.celfit.was.monitoring.BrandPostCampaignRepository;
import com.celfit.was.monitoring.MonitoringApiException;
import com.celfit.was.monitoring.MonitoringCommandClient;
import com.celfit.was.monitoring.MonitoringUnavailableException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * direct 게시물 이관 잡(2026-08-18 direct 통합 §M2, 설계 §결정 4-2) — 레거시 direct 매핑
 * ({@code app.brand_direct_posts}, {@code migrated_at IS NULL})을 monitoring 브랜드 풀로
 * 재수집시킨다. {@code POST /api/brands/{brandId}/direct-posts}를 {@code importLegacyHistory=true}로
 * 호출하면, monitoring이 그 안에서 레거시 이력(post_snapshot·post_meta·post_comment)을 브랜드
 * 테이블로 복사한 뒤 평소 경로(fetchPost + savePost + enrich)를 태워 오늘자 스냅샷·게시자 프로필·
 * {@code enriched_at}까지 확보한다(설계 §4-2 — "링크만 옮기고 다음 스윕에 맡기기"를 기각한 이유는
 * 그러면 이관 직후 구간이 가장 비대칭인 상태가 되기 때문).
 *
 * <p><b>운영 실행 절차</b>(계획 문서 §M): 배포 직후 사용자 승인 후 1회 실행한다. was가 완전히
 * 롤아웃된 뒤 돌려야 한다 — 그 전에 돌리면 신 파드만 이관된 매핑을 브랜드 풀로 서빙하고 구 파드는
 * 여전히 레거시 경로로 서빙해 일시적으로 두 파드가 다른 카드를 보여줄 수 있다(무해하지만 낭비).
 * {@code POST /v1/admin/brand-direct-posts/migrate}(ADMIN 세션)로 트리거한다
 * ({@link com.celfit.was.v1.admin.AdminBrandDirectPostMigrationController}). 완료 판정은 계획
 * 문서 §M2를 참고 — {@code SELECT count(*) FROM app.brand_direct_posts WHERE migrated_at IS NULL}
 * 이 0이 될 때까지 재실행(멱등, 안전)한다.
 *
 * <p><b>멱등</b>: {@code migrated_at IS NULL} 조건으로 조회하므로 이미 이관된 행은 자연히 대상에서
 * 빠진다. 브랜드별 shortcode dedupe — 같은 게시물을 여러 유저가 등록했을 수 있어 monitoring 호출은
 * (brandId, shortCode) 조합당 1회만 나간다. {@code registeredAt}은 그 조합 중 가장 이른
 * {@code created_at}을 쓴다(실제 최초 등록 시점에 가장 가깝다). 캠페인 연결은 dedupe하지
 * 않는다 — 원 매핑 각각(유저별 캠페인)이 {@code brand_post_campaigns}에 개별 행으로 남아야 한다.
 *
 * <p>404({@code POST_NOT_FOUND})·422({@code PRIVATE_ACCOUNT}·{@code POST_UNSUPPORTED})는 게시물이
 * 이미 사라진 확정 실패라 {@code migrated_at}을 찍어 무한 재시도를 막는다. 503(monitoring 불능)·
 * 그 외 예기치 못한 오류는 건너뛰고 다음 실행이 재시도한다.
 */
@Component
@ConditionalOnProperty(name = "monitoring.enabled", havingValue = "true")
public class BrandDirectPostMigrationJob {

	private static final Logger log = LoggerFactory.getLogger(BrandDirectPostMigrationJob.class);

	private final BrandDirectPostRepository directPostRepository;
	private final BrandPostCampaignRepository postCampaignRepository;
	private final MonitoringCommandClient commandClient;

	public BrandDirectPostMigrationJob(BrandDirectPostRepository directPostRepository,
			BrandPostCampaignRepository postCampaignRepository, MonitoringCommandClient commandClient) {
		this.directPostRepository = directPostRepository;
		this.postCampaignRepository = postCampaignRepository;
		this.commandClient = commandClient;
	}

	/** 실행 결과 집계 — 어드민 응답 바디 겸 로그 요약. */
	public record Result(int targets, int migrated, int permanentlyFailed, int skipped) {
	}

	/** 동기 실행 — 대상 건수만큼 최대 5콜씩 순차로 나간다(설계 §5-2). 어드민이 결과를 바로 확인한다. */
	public Result run() {
		Map<BrandShortCode, List<PendingMigrationRow>> grouped = groupByBrandAndShortCode();

		int migrated = 0;
		int permanentlyFailed = 0;
		int skipped = 0;
		for (Map.Entry<BrandShortCode, List<PendingMigrationRow>> entry : grouped.entrySet()) {
			BrandShortCode key = entry.getKey();
			List<PendingMigrationRow> rows = entry.getValue();
			switch (migrateOne(key, rows)) {
				case MIGRATED -> migrated++;
				case PERMANENTLY_FAILED -> permanentlyFailed++;
				case SKIPPED -> skipped++;
			}
		}
		log.info("direct 게시물 이관 잡 완료 — 대상 {}건, 이관 {}건, 확정실패(404/422) {}건, 건너뜀(재시도 대상) {}건",
				grouped.size(), migrated, permanentlyFailed, skipped);
		return new Result(grouped.size(), migrated, permanentlyFailed, skipped);
	}

	private Map<BrandShortCode, List<PendingMigrationRow>> groupByBrandAndShortCode() {
		Map<BrandShortCode, List<PendingMigrationRow>> grouped = new LinkedHashMap<>();
		for (PendingMigrationRow row : directPostRepository.findAllPending()) {
			grouped.computeIfAbsent(new BrandShortCode(row.brandId(), row.shortCode()), k -> new ArrayList<>())
					.add(row);
		}
		return grouped;
	}

	private Outcome migrateOne(BrandShortCode key, List<PendingMigrationRow> rows) {
		OffsetDateTime registeredAt = rows.stream().map(PendingMigrationRow::createdAt)
				.min(OffsetDateTime::compareTo)
				.orElseThrow();
		try {
			commandClient.registerDirectPost(key.brandId(), key.shortCode(), registeredAt, true);
			directPostRepository.markMigrated(key.brandId(), key.shortCode());
			for (PendingMigrationRow row : rows) {
				if (row.campaignId() != null) {
					postCampaignRepository.upsert(key.brandId(), key.shortCode(), row.campaignId(), row.userId());
				}
			}
			return Outcome.MIGRATED;
		} catch (MonitoringApiException e) {
			if (e.httpStatus() == 404 || e.httpStatus() == 422) {
				// 게시물이 이미 사라진 확정 실패 — 무한 재시도 금지, migrated_at을 찍어 정산한다.
				directPostRepository.markMigrated(key.brandId(), key.shortCode());
				log.warn("direct 이관 확정 실패(재시도 안 함) brandId={}, shortCode={}, code={}: {}",
						key.brandId(), key.shortCode(), e.code(), e.getMessage());
				return Outcome.PERMANENTLY_FAILED;
			}
			// 예기치 못한 API 오류 — migrated_at을 찍지 않는다(성급한 확정보다 재시도가 안전).
			log.error("direct 이관 예기치 못한 API 오류 — 건너뛰고 다음 실행이 재시도 brandId={}, shortCode={}, code={}",
					key.brandId(), key.shortCode(), e.code(), e);
			return Outcome.SKIPPED;
		} catch (MonitoringUnavailableException e) {
			log.warn("direct 이관 monitoring 불능 — 건너뛰고 다음 실행이 재시도 brandId={}, shortCode={}: {}",
					key.brandId(), key.shortCode(), e.getMessage());
			return Outcome.SKIPPED;
		}
	}

	private enum Outcome {
		MIGRATED, PERMANENTLY_FAILED, SKIPPED
	}

	private record BrandShortCode(long brandId, String shortCode) {
	}
}

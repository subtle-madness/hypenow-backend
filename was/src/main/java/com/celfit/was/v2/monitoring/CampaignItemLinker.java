package com.celfit.was.v2.monitoring;

import com.celfit.was.monitoring.CampaignRepository;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.v1.common.V1ApiException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 캠페인 연결 전용 슬림 경로 — 소유 검증 2건과 {@code campaign_id} 갱신만 한다(아이템당 3쿼리).
 *
 * <p><b>왜 레거시 patch를 안 쓰나(설계 원칙과의 트레이드오프)</b>: 스펙(2026-08-07 §8)의 구현은
 * 원래 {@code V1MonitoringItemUpdateService.patch} 재사용이었다 — 레거시 검증을 공짜로 얻는
 * 대신, patch는 응답 전량 재조립(스냅샷·댓글·프로필 배치 조회)까지 돌아 아이템당 ~9쿼리다.
 * v2 콘텐츠 추가는 그 응답을 버리고 id만 쓰므로(입력 상한 100건 × 9쿼리가 한 트랜잭션에 몰려
 * 커넥션 풀 고갈 위험 — Task 11 사후 리뷰 Important #2), 조립을 떼어낸 전용 경로로 바꾼다.
 * 단 patch가 하던 <b>두 소유 검증(캠페인·아이템 {@code findByIdAndUser})은 여기서 그대로
 * 유지한다</b> — 절감 대상은 조립이지 검증이 아니다. 에러 메시지도 레거시와 동일하게 낸다.
 *
 * <p>레거시 {@code /v1/monitoring/**} 는 계속 patch를 쓴다(0줄 변경) — 이 컴포넌트는 v2 전용이다.
 */
@Component
public class CampaignItemLinker {

	private final CampaignRepository campaignRepository;
	private final MonitoringItemRepository itemRepository;

	public CampaignItemLinker(CampaignRepository campaignRepository, MonitoringItemRepository itemRepository) {
		this.campaignRepository = campaignRepository;
		this.itemRepository = itemRepository;
	}

	/** 캠페인 연결 — 캠페인·아이템 소유 검증 후 {@code campaign_id}만 갱신한다. */
	@Transactional
	public void link(long userId, long campaignId, long itemId) {
		if (campaignRepository.findByIdAndUser(campaignId, userId).isEmpty()) {
			throw V1ApiException.notFound("캠페인을 찾을 수 없습니다.");
		}
		requireItem(userId, itemId);
		itemRepository.updateCampaign(itemId, campaignId);
	}

	/** 연결 해제 — 아이템 소유 검증 후 {@code campaign_id}를 비운다(대상 캠페인이 없으므로 검증도 1건). */
	@Transactional
	public void unlink(long userId, long itemId) {
		requireItem(userId, itemId);
		itemRepository.updateCampaign(itemId, null);
	}

	private void requireItem(long userId, long itemId) {
		if (itemRepository.findByIdAndUser(itemId, userId).isEmpty()) {
			throw V1ApiException.notFound("대상을 찾을 수 없습니다.");
		}
	}
}

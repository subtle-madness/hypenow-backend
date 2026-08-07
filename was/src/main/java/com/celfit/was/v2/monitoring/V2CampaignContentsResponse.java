package com.celfit.was.v2.monitoring;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 캠페인 콘텐츠 추가 응답(스펙 §8) — {@code POST /v2/monitoring/campaigns/{campaignId}/contents}의
 * {@code data}. 요청의 {@code contentIds} 순서 그대로 entry 단위 판정을 돌려준다(부분 성공).
 *
 * <p>nullable 필드는 계약 무결성 규칙 #1(1.8)대로 키를 생략하지 않고 명시적 null로 직렬화한다
 * (record 기본 동작 — NON_NULL 미적용). 즉 {@code monitoringItemId}·{@code reasonCode}·
 * {@code reason}은 해당 없음일 때도 키가 존재한다.
 */
public record V2CampaignContentsResponse(String campaignId, List<Result> results) {

	/**
	 * 콘텐츠 1건의 판정. {@code result} 어휘는 레거시 등록 entry(6.28)와 같은 값 공간을 쓴다 —
	 * FE가 직접 등록(§6-4)에서 이미 소비 중인 어휘라 캠페인 화면만 다른 단어를 갖게 하지 않는다.
	 *
	 * @param contentId         요청에 들어온 값 그대로(=shortcode, canonicalPostId)
	 * @param monitoringItemId  판정에 대응하는 레거시 추적 아이템 id(확정 못 하면 null)
	 */
	public record Result(
			String contentId,
			@Schema(allowableValues = {"success", "pending", "duplicate", "failed"}) String result,
			String monitoringItemId,
			String reasonCode,
			String reason) {
	}
}

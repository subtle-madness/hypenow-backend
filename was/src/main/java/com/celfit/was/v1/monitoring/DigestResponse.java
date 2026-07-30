package com.celfit.was.v1.monitoring;

import java.util.List;

/**
 * 알림 다이제스트 응답(스펙 6.32). 순수 DTO — jsonb 파싱·조립은 {@link DigestAssembler} 몫이다
 * (리포 관용구: DTO record는 순수, jsonb 파싱은 @Component Assembler가 소유 — PostDetailAssembler·
 * V1ContentReportAssembler 등 전례를 따른다).
 *
 * <p>readAt은 계약 무결성 규칙 #1(1.8)이 짚은 그 사례다 — 키를 생략하면 프론트가 전 알림을
 * 읽음으로 오판해 안읽음 배지가 영구히 0이 된다. record 기본 동작(NON_NULL 미적용)으로 키를
 * 항상 유지한다.
 */
public record DigestResponse(String id, String date, String createdAt, String readAt, List<Item> items) {

	public record Item(String category, String type, String summary, int count) {
	}
}

package com.celfit.was.monitoring;

import java.util.List;

/**
 * 알람 메일 문안 조립 — 임시(test) 문안(사용자 결정 2026-07-29). 정식 문안·딥링크는
 * 프론트 기획 확정 후 이 클래스만 교체하면 된다(잡 로직과 분리 목적).
 */
public class MonitoringAlarmMailComposer {

	public String subject(int count) {
		return "[hypenow] 모니터링 알림 — 새 게시물 감지 " + count + "건";
	}

	public String body(List<PendingCandidate> candidates) {
		StringBuilder body = new StringBuilder("등록하신 캠페인에서 조건에 맞는 게시물이 감지되었습니다.\n\n");
		for (PendingCandidate candidate : candidates) {
			body.append("- @").append(candidate.username())
					.append(" — 게시물 ").append(candidate.shortCode()).append('\n');
			if (candidate.captionExcerpt() != null) {
				body.append("  ").append(candidate.captionExcerpt()).append('\n');
			}
		}
		body.append("\nhypenow 콘텐츠 모니터링에서 확인 후 승인/기각해 주세요. (임시 안내 메일)");
		return body.toString();
	}
}

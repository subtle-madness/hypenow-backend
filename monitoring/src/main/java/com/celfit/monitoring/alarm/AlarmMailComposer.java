package com.celfit.monitoring.alarm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 메일 문안 조립 — **임시 카피**다(스펙 §1-6). 정식 문안과 딥링크는 프론트 경로 확정 후 교체하며,
 * 교체 지점이 이 클래스 하나로 모이도록 발송 잡에서 분리해 뒀다.
 */
@Component
public class AlarmMailComposer {

	private static final Logger log = LoggerFactory.getLogger(AlarmMailComposer.class);
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** 화면 문구 — 스펙 §1-3 표와 1:1. */
	private static final Map<AlarmEventType, String> HEADINGS = Map.of(
			AlarmEventType.COLLECTION_STARTED, "게시물 수집 시작",
			AlarmEventType.COLLECTION_ENDED, "게시물 수집 종료",
			AlarmEventType.METRICS_HIDDEN, "일부 지표 비공개",
			AlarmEventType.CONTENT_UNAVAILABLE, "콘텐츠 비공개/삭제/수집 오류");

	/** 지표 키 → 사용자 어휘. 영문 키가 그대로 나가면 메일이 로그처럼 보인다. */
	private static final Map<String, String> METRIC_LABELS = Map.of(
			"likes", "좋아요", "comments", "댓글", "views", "조회수",
			"saves", "저장", "shares", "공유", "reposts", "리포스트");

	public record Mail(String subject, String text) {}

	public Mail compose(List<AlarmEvent> events) {
		Map<AlarmEventType, StringBuilder> sections = new LinkedHashMap<>();
		for (AlarmEvent event : events) {
			sections.computeIfAbsent(event.eventType(), t -> new StringBuilder())
					.append("- ").append(line(event)).append('\n');
		}
		StringBuilder text = new StringBuilder("안녕하세요. hypenow 모니터링 알림입니다.\n");
		sections.forEach((type, body) -> text.append('\n')
				.append("■ ").append(HEADINGS.get(type)).append('\n').append(body));
		text.append("\n※ 알림 설정은 hypenow 웹에서 변경할 수 있습니다.\n");
		return new Mail("[hypenow] 모니터링 알림 " + events.size() + "건", text.toString());
	}

	/** 한 줄 요약. payload가 깨져도 조립은 계속한다 — 문안 실패로 알람 전체가 멈추면 안 된다. */
	private String line(AlarmEvent event) {
		JsonNode payload = parse(event.payload());
		String username = payload.path("username").asString("(계정 미상)");
		String shortCode = payload.path("shortCode").asString(null);
		StringBuilder line = new StringBuilder("@").append(username);
		if (shortCode != null) {
			line.append(" · ").append(shortCode);
		}
		if (event.eventType() == AlarmEventType.METRICS_HIDDEN) {
			List<String> labels = new java.util.ArrayList<>();
			payload.path("metrics").forEach(m -> labels.add(
					METRIC_LABELS.getOrDefault(m.asString(""), m.asString(""))));
			if (!labels.isEmpty()) {
				line.append(" (").append(String.join(", ", labels)).append(')');
			}
		}
		String failReason = payload.path("failReason").asString(null);
		if (failReason != null) {
			line.append(" (").append(failReason).append(')');
		}
		return line.toString();
	}

	private JsonNode parse(String payload) {
		try {
			return JSON.readTree(payload == null ? "{}" : payload);
		} catch (RuntimeException e) {
			log.warn("알람 payload 파싱 실패 — 빈 값으로 문안을 만든다: {}", e.getMessage());
			return JSON.createObjectNode();
		}
	}
}

package com.celfit.monitoring.alarm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 임시 문안 — 정식 카피·딥링크는 후속이라 여기서 지키는 건 "재료가 다 실리는가"뿐이다. */
class AlarmMailComposerTest {

	private static final Instant T = Instant.parse("2026-07-30T00:00:00Z");

	private final AlarmMailComposer composer = new AlarmMailComposer();

	private static AlarmEvent event(long id, AlarmEventType type, String payload) {
		return new AlarmEvent(id, 1L, 7L, type, payload, T, T, AlarmEmailStatus.PENDING, null);
	}

	@Test
	void 유저_한_통에_이벤트_종류별_구획이_실린다() {
		var mail = composer.compose(List.of(
				event(1, AlarmEventType.COLLECTION_STARTED,
						"{\"username\":\"acct_a\",\"shortCode\":\"SC1\"}"),
				event(2, AlarmEventType.METRICS_HIDDEN,
						"{\"username\":\"acct_a\",\"shortCode\":\"SC1\",\"metrics\":[\"views\",\"saves\"]}")));

		assertThat(mail.subject()).contains("2건");
		assertThat(mail.text())
				.contains("게시물 수집 시작")
				.contains("일부 지표 비공개")
				.contains("@acct_a")
				.contains("SC1")
				// 지표 이름은 사용자 화면 어휘로 — 영문 키가 그대로 나가면 메일이 로그처럼 보인다
				.contains("조회수")
				.contains("저장");
	}

	@Test
	void 콘텐츠_이용불가는_사유를_함께_보여준다() {
		var mail = composer.compose(List.of(event(1, AlarmEventType.CONTENT_UNAVAILABLE,
				"{\"username\":\"acct_a\",\"shortCode\":\"SC1\",\"failReason\":\"PRIVATE_ACCOUNT\"}")));

		assertThat(mail.text()).contains("콘텐츠 비공개/삭제/수집 오류").contains("PRIVATE_ACCOUNT");
	}

	/** payload가 깨져도 메일은 나가야 한다 — 문안 조립 실패가 발송 전체를 막으면 알람이 통째로 멈춘다. */
	@Test
	void 깨진_payload도_문안을_만든다() {
		var mail = composer.compose(List.of(event(1, AlarmEventType.COLLECTION_ENDED, "{}")));

		assertThat(mail.text()).contains("게시물 수집 종료");
	}
}

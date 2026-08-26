package com.celfit.was.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestStageTimingsTest {

	@Test
	void 같은_단계는_합산되고_호출수를_센다() {
		RequestStageTimings.begin();
		RequestStageTimings.record("Repo.find", 1_000_000);
		RequestStageTimings.record("Repo.find", 2_000_000);
		RequestStageTimings.record("Repo.other", 5_000_000);
		Map<String, long[]> stages = RequestStageTimings.end();

		assertThat(stages).containsOnlyKeys("Repo.find", "Repo.other");
		assertThat(stages.get("Repo.find")[0]).isEqualTo(3_000_000);
		assertThat(stages.get("Repo.find")[1]).isEqualTo(2);
		assertThat(stages.get("Repo.other")[1]).isEqualTo(1);
	}

	@Test
	void begin_없는_기록은_무시된다() {
		// 요청 밖 스레드(스케줄 잡·부팅) 시나리오 — 예외 없이 조용히 무시돼야 한다.
		RequestStageTimings.record("Repo.background", 1_000_000);
		assertThat(RequestStageTimings.end()).isEmpty();
	}

	@Test
	void end는_누적기를_비활성화한다() {
		RequestStageTimings.begin();
		RequestStageTimings.record("Repo.find", 1_000_000);
		RequestStageTimings.end();
		// end 이후 기록은 다음 begin 전까지 무시 — 톰캣 스레드 재사용 오염 방지.
		RequestStageTimings.record("Repo.late", 1_000_000);
		assertThat(RequestStageTimings.end()).isEmpty();
	}
}

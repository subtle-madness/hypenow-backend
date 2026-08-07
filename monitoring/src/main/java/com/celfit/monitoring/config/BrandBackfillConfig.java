package com.celfit.monitoring.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 브랜드 등록 백필(태그 스펙 §5) 전용 executor — 등록 동기 응답(was 10초 read timeout 예산)
 * 밖에서 백필 열거(최대 5페이지 + 게시자 ~수십 콜 + 댓글)를 돌린다.
 *
 * <p>캠페인 등록의 metricsBackfillExecutor와 분리하는 이유: 브랜드 백필 1건은 수십 초~분 단위
 * 콜 체인이라 공유하면 캠페인 등록 백필(최대 ~1분)이 그 뒤에 줄을 선다. 단일 스레드·데몬은
 * 같은 관용구다 — 직렬화가 Hiker 부하 완충이 되고, 종료로 백필이 끊겨도 last_tracked_on이
 * null로 남아 다음 스윕이 트래킹으로 백스톱한다.
 */
@Configuration
public class BrandBackfillConfig {

	@Bean(name = "brandBackfillExecutor")
	public Executor brandBackfillExecutor() {
		return Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "brand-backfill");
			t.setDaemon(true);
			return t;
		});
	}
}

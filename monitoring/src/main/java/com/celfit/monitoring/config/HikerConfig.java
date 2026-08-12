package com.celfit.monitoring.config;

import com.celfit.monitoring.hiker.BrandCallContext;
import com.celfit.monitoring.hiker.CountingHikerHttp;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.HikerHttp;
import com.celfit.monitoring.hiker.RecordingHikerHttp;
import com.celfit.monitoring.hiker.TargetCallContext;
import com.celfit.monitoring.store.BrandCallCountRepository;
import com.celfit.monitoring.store.RawPayloadRepository;
import com.celfit.monitoring.store.TargetCallCountRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hiker 수집 경로 조립 — 전송(HikerHttp)을 원형 적재·브랜드 콜 집계 데코레이터로 감싼 뒤
 * 파서(HikerClient)에 물린다.
 * HikerClient를 @Component가 아니라 여기서 만드는 이유: 감싸지 않은 전송이 파서에 직접 물릴 여지를 없앤다.
 * 주입되는 delegate는 전송 구현 하나뿐이라(운영은 JdkHikerHttp, 테스트는 @Primary fake) 테스트 fake도 그대로 감싸진다.
 */
@Configuration
public class HikerConfig {

	@Bean
	public HikerClient hikerClient(HikerHttp transport, RawPayloadRepository rawPayloads,
			BrandCallContext brandContext, BrandCallCountRepository brandCounts,
			TargetCallContext targetContext, TargetCallCountRepository targetCounts) {
		// 집계가 바깥 — 원형 적재까지 끝난 "호출자가 성공으로 본 콜"과 집계가 1:1로 맞는다.
		return new HikerClient(new CountingHikerHttp(new RecordingHikerHttp(transport, rawPayloads),
				brandContext, brandCounts, targetContext, targetCounts));
	}
}

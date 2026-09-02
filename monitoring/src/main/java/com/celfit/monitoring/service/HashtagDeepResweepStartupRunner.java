package com.celfit.monitoring.service;

import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 해시태그 딥 재백필 1회 러너(2026-09-02 감시 세트 2,000 설계 §2) — 구 편입 하드스톱(1,000)
 * 기간에 버려진 게시물을 상한 상향 직후 한 번 회수한다. {@link UnenrichedBackfillStartupRunner}와
 * 동형 골격(기동 완료 후 데몬 스레드, 브랜드 단위 격리)이되 <b>기본 꺼짐</b>이다 — dedup을 무시하는
 * 딥 열거는 no-op이 아니라 매 재기동마다 태그당 수십 페이지 콜을 낸다. 운영 절차: 상향 배포 시
 * env로 {@code MONITORING_BRAND_HASHTAG_DEEP_RESWEEP_ON_STARTUP=true} 1회 주입 → 완료 로그 확인
 * 후 다음 배포에서 제거.
 */
@Component
public class HashtagDeepResweepStartupRunner {

	private static final Logger log = LoggerFactory.getLogger(HashtagDeepResweepStartupRunner.class);

	private final BrandRepository brands;
	private final BrandHashtagCollectService hashtagCollect;
	private final boolean enabled;

	public HashtagDeepResweepStartupRunner(BrandRepository brands,
			BrandHashtagCollectService hashtagCollect,
			@Value("${monitoring.brand.hashtag.deep-resweep-on-startup:false}") boolean enabled) {
		this.brands = brands;
		this.hashtagCollect = hashtagCollect;
		this.enabled = enabled;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		if (!enabled) {
			return;
		}
		Thread t = new Thread(this::runSafely, "brand-hashtag-deep-resweep-startup");
		t.setDaemon(true);
		t.start();
	}

	private void runSafely() {
		List<BrandRow> active = brands.findActive();
		int failures = 0;
		for (BrandRow b : active) {
			try {
				hashtagCollect.deepResweep(b);
			} catch (RuntimeException e) {
				failures++;
				log.warn("해시태그 딥 재백필 실패(격리) — {}: {}", b.username(), e.toString());
			}
		}
		log.info("해시태그 딥 재백필 완료 — 브랜드 {}건 중 실패 {}건", active.size(), failures);
	}
}

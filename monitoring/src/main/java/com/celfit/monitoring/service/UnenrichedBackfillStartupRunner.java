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
 * 미보강 이관분 기동 즉시 백필 러너(2026-08-28 사용자 지시) — 앱 기동 완료
 * ({@link ApplicationReadyEvent}) 시 활성 브랜드 전부를 순회하며
 * {@link BrandDirectCollectService#backfillUnenriched}로 미보강(enriched_at IS NULL) 재고를
 * 별도 데몬 스레드에서 전량 즉시 처리한다. 해시태그 직접 수집 전환이 구 감지 데이터를
 * {@code brand_tagged_post}로 이관하며 남긴 미보강 백로그를 다음 야간 스윕(2단계 상한 기본
 * 2,000건/스윕 — {@code monitoring.brand.unenumerated-sweep-limit}, F6 주석 정정 2026-09-02)의
 * 점진 소진에 맡기지 않는 것이 목적이다 — {@link AdDisclosureBackfillStartupRunner}와 동형 골격이다.
 *
 * <p>부팅을 블로킹하지 않는다 — {@link ApplicationReadyEvent} 리스너 자체는 즉시 반환하고, 실제
 * 백필은 새로 띄운 데몬 스레드에서 돈다. 킬 스위치 {@code monitoring.brand.unenriched-backfill-on-startup=false}면
 * 스레드조차 띄우지 않고 스킵한다. 브랜드 단위 실패는 격리한다 — 한 브랜드의 백필 실패가 나머지
 * 브랜드의 백필을 막지 않는다({@link BrandSweepJob#runSweep}과 같은 격리 관용구).
 *
 * <p>미보강 재고가 없으면 브랜드마다 빈 목록 조회 한 번으로 끝나는 no-op이다 — 평시 재기동(배포
 * 롤링 등)마다 매번 도는 러너지만 비용이 사실상 없다.
 *
 * <p>가드 있음(2026-08-28 리뷰 지적) — {@link BrandDirectCollectService#unenumeratedBusy}가 이
 * 러너와 야간 스윕 2단계({@code sweepUnenumerated})의 동시 실행을 브랜드 호출 단위로 막는다.
 * 겹치면 그 브랜드는 이번 호출에서 스킵된다(멱등이라 데이터는 안전하고, Hiker 콜 이중 지출만
 * 방지하는 목적 — 스킵된 브랜드는 다음 야간 스윕이나 다음 재기동이 이어받는다).
 */
@Component
public class UnenrichedBackfillStartupRunner {

	private static final Logger log = LoggerFactory.getLogger(UnenrichedBackfillStartupRunner.class);

	private final BrandRepository brands;
	private final BrandDirectCollectService directCollect;
	private final boolean enabled;

	public UnenrichedBackfillStartupRunner(BrandRepository brands, BrandDirectCollectService directCollect,
			@Value("${monitoring.brand.unenriched-backfill-on-startup:true}") boolean enabled) {
		this.brands = brands;
		this.directCollect = directCollect;
		this.enabled = enabled;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		if (!enabled) {
			log.info("미보강 이관분 기동 백필 스킵 — enabled=false");
			return;
		}
		Thread t = new Thread(this::runSafely, "brand-unenriched-backfill-startup");
		t.setDaemon(true);
		t.start();
	}

	private void runSafely() {
		List<BrandRow> active = brands.findActive();
		int failures = 0;
		int totalBackfilled = 0;
		for (BrandRow b : active) {
			try {
				int backfilled = directCollect.backfillUnenriched(b);
				totalBackfilled += backfilled;
				log.info("미보강 이관분 기동 백필 진행 — 브랜드 {} {}건 시도", b.username(), backfilled);
			} catch (RuntimeException e) {
				failures++;
				log.warn("미보강 이관분 기동 백필 실패(격리) — {}: {}", b.username(), e.toString());
			}
		}
		log.info("미보강 이관분 기동 백필 완료 — 브랜드 {}건 중 실패 {}건, 총 {}건 백필",
				active.size(), failures, totalBackfilled);
	}
}

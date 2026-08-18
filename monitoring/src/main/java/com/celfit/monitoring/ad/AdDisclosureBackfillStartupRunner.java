package com.celfit.monitoring.ad;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 광고 판정 미판정 백필 기동 러너(2026-08-18, 상한 제거 개정) — 앱 기동 완료
 * ({@link ApplicationReadyEvent}) 시 {@link AdDisclosureJudgeService#backfillUnjudged}를
 * 별도 데몬 스레드에서 즉시 시작한다. 배포 직후 재고(운영 재기동 사이 쌓인 미판정 게시물)가
 * 다음 야간 스윕까지 기다리지 않고 바로 판정되는 것이 목적이다.
 *
 * <p>부팅을 블로킹하지 않는다 — {@link ApplicationReadyEvent} 리스너 자체는 즉시 반환하고,
 * 실제 백필은 새로 띄운 데몬 스레드에서 돈다. 킬 스위치
 * {@code monitoring.brand.ad-disclosure.enabled=false}면 스레드조차 띄우지 않고 스킵한다.
 * 실패는 이 스레드 안에서 격리한다 — {@link AdDisclosureJudgeService#backfillUnjudged}가
 * 게시물 단위로 이미 격리하지만, 그 바깥(예: {@code countUnjudged} DB 조회 자체 실패)도 여기서
 * 한 번 더 감싸 앱 기동·이후 동작에 전혀 영향을 주지 않는다.
 *
 * <p>야간 스윕 말미의 백필 훅({@code BrandSweepJob#backfillAdDisclosuresSafely})과 동시에
 * 돌 수 있다 — {@link AdDisclosureJudgeService}의 {@code backfillRunning} 가드가 겹침을
 * 막는다(이미 실행 중이면 나중 호출은 즉시 스킵).
 */
@Component
public class AdDisclosureBackfillStartupRunner {

	private static final Logger log = LoggerFactory.getLogger(AdDisclosureBackfillStartupRunner.class);

	private final AdDisclosureJudgeService adJudge;
	private final boolean enabled;

	public AdDisclosureBackfillStartupRunner(AdDisclosureJudgeService adJudge,
			@Value("${monitoring.brand.ad-disclosure.enabled:true}") boolean enabled) {
		this.adJudge = adJudge;
		this.enabled = enabled;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady() {
		if (!enabled) {
			log.info("광고 판정 기동 백필 스킵 — enabled=false");
			return;
		}
		Thread t = new Thread(this::runSafely, "brand-ad-disclosure-startup-backfill");
		t.setDaemon(true);
		t.start();
	}

	private void runSafely() {
		try {
			AdDisclosureJudgeService.BackfillOutcome outcome = adJudge.backfillUnjudged();
			log.info("광고 판정 기동 백필 완료 — 시작 시점 잔여 {}건 중 {}건 처리", outcome.remaining(), outcome.processed());
		} catch (RuntimeException e) {
			log.error("광고 판정 기동 백필 실패(격리, 앱 기동·운영에는 영향 없음): {}", e.toString());
		}
	}
}

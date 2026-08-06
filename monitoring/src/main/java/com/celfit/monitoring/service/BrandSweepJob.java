package com.celfit.monitoring.service;

import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 브랜드 태그 일일 스윕(2026-08-06 스펙 §3) — 활성 브랜드 순회: 트래킹 도래(3일 주기)면
 * track(105개 깊이 — 감지 겸함), 아니면 detect(1페이지 1콜). 실패는 브랜드 단위로 격리하고
 * 재시도 라운드는 두지 않는다 — 트래킹 실패는 last_tracked_on을 갱신하지 않으므로 다음날
 * 스윕이 다시 트래킹으로 백스톱한다(캠페인 스윕의 라운드 재시도보다 단순한 회복 모델).
 *
 * <p>계정 삭제·비공개 전환(SubjectNotFound·PrivateAccount)도 상태 전이 없이 격리만 한다 —
 * 브랜드 추적은 탈퇴(CLOSED)까지가 정본이라(스펙 §8) 캠페인의 hidden 전이를 승계하지 않는다.
 * 태그 열거 404(태그 0건)는 HikerClient.fetchTaggedPage가 이미 빈 페이지로 삼킨다.
 */
@Service
public class BrandSweepJob {

	private static final Logger log = LoggerFactory.getLogger(BrandSweepJob.class);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final BrandRepository brands;
	private final BrandCollectService collect;
	private final int trackingIntervalDays;

	public BrandSweepJob(BrandRepository brands, BrandCollectService collect,
			@Value("${monitoring.brand.tracking-interval-days:3}") int trackingIntervalDays) {
		this.brands = brands;
		this.collect = collect;
		this.trackingIntervalDays = trackingIntervalDays;
	}

	public void run() {
		LocalDate today = LocalDate.now(KST);
		List<BrandRow> active = brands.findActive();
		int failures = 0;
		for (BrandRow b : active) {
			try {
				if (trackingDue(b, today)) {
					collect.track(b);
					brands.touchTracked(b.id(), today);   // 성공 시에만 — 실패 브랜드는 내일 다시 트래킹
				} else {
					collect.detect(b);
				}
			} catch (RuntimeException e) {
				failures++;
				log.warn("브랜드 스윕 실패(격리) — {}: {}", b.username(), e.toString());
			}
		}
		log.info("브랜드 태그 스윕 완료 — 브랜드 {}건 중 실패 {}건", active.size(), failures);
	}

	/** last_tracked_on null은 백필 미완(등록 직후 비동기 백필 실패 포함) — 트래킹으로 백스톱한다. */
	private boolean trackingDue(BrandRow b, LocalDate today) {
		return b.lastTrackedOn() == null
				|| !today.isBefore(b.lastTrackedOn().plusDays(trackingIntervalDays));
	}
}

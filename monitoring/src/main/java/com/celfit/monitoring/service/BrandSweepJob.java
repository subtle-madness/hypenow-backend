package com.celfit.monitoring.service;

import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 브랜드 태그 일일 스윕(2026-08-06 스펙 + 매일 전량 개정) — 활성 브랜드 전부를 매일 전량
 * 수집한다(감지/트래킹 구분 없음, {@link BrandCollectService#sweep}). 실패는 브랜드 단위로
 * 격리하고 재시도 라운드는 두지 않는다 — 실패 브랜드는 last_swept_on이 갱신되지 않은 채
 * 다음날 스윕이 자연 백스톱한다(08-06 운영 결정: 현행 유지).
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

	public BrandSweepJob(BrandRepository brands, BrandCollectService collect) {
		this.brands = brands;
		this.collect = collect;
	}

	public void run() {
		LocalDate today = LocalDate.now(KST);
		List<BrandRow> active = brands.findActive();
		int failures = 0;
		for (BrandRow b : active) {
			try {
				collect.sweep(b);
				brands.touchSwept(b.id(), today);   // 성공 시에만 — 실패 브랜드는 "준비 중"으로 남는다
			} catch (RuntimeException e) {
				failures++;
				log.warn("브랜드 스윕 실패(격리) — {}: {}", b.username(), e.toString());
			}
		}
		log.info("브랜드 태그 스윕 완료 — 브랜드 {}건 중 실패 {}건", active.size(), failures);
	}
}

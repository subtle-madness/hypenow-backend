package com.celfit.monitoring.service;

import com.celfit.monitoring.image.AuthorProfileImageArchiveJob;
import com.celfit.monitoring.image.BrandProfileImageArchiveJob;
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
	private final AuthorProfileImageArchiveJob authorImageArchive;
	private final BrandProfileImageArchiveJob brandImageArchive;

	public BrandSweepJob(BrandRepository brands, BrandCollectService collect,
			AuthorProfileImageArchiveJob authorImageArchive, BrandProfileImageArchiveJob brandImageArchive) {
		this.brands = brands;
		this.collect = collect;
		this.authorImageArchive = authorImageArchive;
		this.brandImageArchive = brandImageArchive;
	}

	/**
	 * 이미지 아카이브 두 잡(게시자 {@link AuthorProfileImageArchiveJob}·브랜드 본인 {@link
	 * BrandProfileImageArchiveJob})은 finally 안에서 마지막 단계로 돈다(캠페인 {@code DailySweepJob}과
	 * 동형) — 별도 크론이 아니라 스윕이 갓 재조회한 신선한 URL을 바로 잡기 위함이다. 아카이브
	 * 실패는 잡별 격리 래퍼가 전부 삼켜 스윕 결과에도, 서로에게도 영향을 주지 않는다.
	 */
	public void run() {
		try {
			runSweep();
		} finally {
			runArchiveSafely("게시자 프로필 이미지", authorImageArchive::run);
			runArchiveSafely("브랜드 프로필 이미지", brandImageArchive::run);
		}
	}

	private void runSweep() {
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

	/** 건 단위가 아니라 잡 전체를 격리한다 — 스윕 결과와 무관한 부수 작업이라 예외를 밖으로 내지 않는다. */
	private void runArchiveSafely(String name, Runnable archive) {
		try {
			archive.run();
		} catch (RuntimeException e) {
			log.warn("{} 아카이브 잡 실행 실패(격리) — 스윕 결과에는 영향 없음: {}", name, e.toString());
		}
	}
}

package com.celfit.monitoring.service;

import com.celfit.monitoring.ad.AdDisclosureBackfillStartupRunner;
import com.celfit.monitoring.ad.AdDisclosureJudgeService;
import com.celfit.monitoring.image.AuthorProfileImageArchiveJob;
import com.celfit.monitoring.image.BrandPostThumbnailArchiveJob;
import com.celfit.monitoring.image.BrandProfileImageArchiveJob;
import com.celfit.monitoring.image.HashtagPostAuthorImageArchiveJob;
import com.celfit.monitoring.image.HashtagPostThumbnailArchiveJob;
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
	private final BrandHashtagCollectService hashtagCollect;
	private final AuthorProfileImageArchiveJob authorImageArchive;
	private final BrandProfileImageArchiveJob brandImageArchive;
	private final BrandPostThumbnailArchiveJob brandPostThumbnailArchive;
	private final HashtagPostThumbnailArchiveJob hashtagPostThumbnailArchive;
	private final HashtagPostAuthorImageArchiveJob hashtagPostAuthorImageArchive;
	private final AdDisclosureJudgeService adJudge;
	private final boolean adDisclosureEnabled;

	public BrandSweepJob(BrandRepository brands, BrandCollectService collect,
			BrandHashtagCollectService hashtagCollect, AuthorProfileImageArchiveJob authorImageArchive,
			BrandProfileImageArchiveJob brandImageArchive, BrandPostThumbnailArchiveJob brandPostThumbnailArchive,
			HashtagPostThumbnailArchiveJob hashtagPostThumbnailArchive,
			HashtagPostAuthorImageArchiveJob hashtagPostAuthorImageArchive, AdDisclosureJudgeService adJudge,
			@Value("${monitoring.brand.ad-disclosure.enabled:true}") boolean adDisclosureEnabled) {
		this.brands = brands;
		this.collect = collect;
		this.hashtagCollect = hashtagCollect;
		this.authorImageArchive = authorImageArchive;
		this.brandImageArchive = brandImageArchive;
		this.brandPostThumbnailArchive = brandPostThumbnailArchive;
		this.hashtagPostThumbnailArchive = hashtagPostThumbnailArchive;
		this.hashtagPostAuthorImageArchive = hashtagPostAuthorImageArchive;
		this.adJudge = adJudge;
		this.adDisclosureEnabled = adDisclosureEnabled;
	}

	/**
	 * 이미지 아카이브 잡들(게시자 {@link AuthorProfileImageArchiveJob}·브랜드 본인 {@link
	 * BrandProfileImageArchiveJob}·게시물 썸네일 {@link BrandPostThumbnailArchiveJob}·해시태그 썸네일
	 * {@link HashtagPostThumbnailArchiveJob}·해시태그 작성자 프로필 {@link
	 * HashtagPostAuthorImageArchiveJob})은 finally 안에서 마지막 단계로 돈다(캠페인 {@code
	 * DailySweepJob}과 동형) — 별도 크론이 아니라 스윕이 갓 재조회한 신선한 URL을 바로 잡기 위함이다.
	 * 아카이브 실패는 잡별 격리 래퍼가 전부 삼켜 스윕 결과에도, 서로에게도 영향을 주지 않는다.
	 *
	 * <p>광고 판정 백필({@link #backfillAdDisclosuresSafely}, 2026-08-18 상한 제거 개정)도 같은
	 * finally에서 브랜드 루프 종료 직후 돈다 — 아카이브 잡들과 같은 이유로, 스윕 본체 성패와
	 * 무관하게 매일 시도돼야 한다. 배포 직후 재고는 기동 즉시 실행되는 {@link
	 * AdDisclosureBackfillStartupRunner}가 이미 흡수하므로, 이 훅은 그 사이 발생한 실패 잔량을
	 * 매일 재시도하는 안전망 역할이다.
	 */
	public void run() {
		try {
			runSweep();
		} finally {
			backfillAdDisclosuresSafely();
			runArchiveSafely("게시자 프로필 이미지", authorImageArchive::run);
			runArchiveSafely("브랜드 프로필 이미지", brandImageArchive::run);
			runArchiveSafely("브랜드 게시물 썸네일", brandPostThumbnailArchive::run);
			runArchiveSafely("해시태그 게시물 썸네일", hashtagPostThumbnailArchive::run);
			runArchiveSafely("해시태그 작성자 프로필 이미지", hashtagPostAuthorImageArchive::run);
		}
	}

	/**
	 * 유저태그 스윕과 해시태그 스윕은 브랜드마다 각자 try/catch로 격리한다 — 한쪽 실패가
	 * touchSwept·failures 카운트에 영향을 주지 않고, 유저태그 스윕이 실패한 브랜드도 해시태그
	 * 스윕은 그대로 시도된다(서로 독립된 수집 경로 — 스펙 §8).
	 */
	private void runSweep() {
		LocalDate today = LocalDate.now(KST);
		List<BrandRow> active = brands.findActive();
		int failures = 0;
		int hashtagFailures = 0;
		for (BrandRow b : active) {
			try {
				collect.sweep(b);
				brands.touchSwept(b.id(), today);   // 성공 시에만 — 실패 브랜드는 "준비 중"으로 남는다
			} catch (RuntimeException e) {
				failures++;
				log.warn("브랜드 스윕 실패(격리) — {}: {}", b.username(), e.toString());
			}
			try {
				hashtagCollect.sweep(b);
			} catch (RuntimeException e) {
				hashtagFailures++;
				log.warn("브랜드 해시태그 스윕 실패(격리) — {}: {}", b.username(), e.toString());
			}
		}
		log.info("브랜드 태그 스윕 완료 — 브랜드 {}건 중 실패 {}건, 해시태그 실패 {}건",
				active.size(), failures, hashtagFailures);
	}

	/**
	 * 광고 판정 백필 안전망(2026-08-18 상한 제거 개정) — 사용자 확정 원칙 "판정은 처음에 전량,
	 * 이후는 캡션 변경분만"의 최초 1회 전량 판정은 기동 즉시 실행되는 {@link
	 * AdDisclosureBackfillStartupRunner}가 맡는다. 이 훅은 그 사이(또는 배포 없이 지나간 하루
	 * 동안) 발생한 <b>실패 잔량 재시도 안전망</b>이다 — 브랜드 스코프 없이 전역
	 * {@code ad_verdict IS NULL} 잔량을 상한 없이 매일 밤 전부 처리한다. 킬
	 * 스위치({@code monitoring.brand.ad-disclosure.enabled})가 꺼져 있으면 스윕 경로의 판정과
	 * 함께 이 백필도 스킵한다(같은 롤백 손잡이로 양쪽을 끈다). {@link
	 * AdDisclosureJudgeService}의 동시 실행 가드 덕에 기동 백필과 겹쳐도 안전하다(나중 호출은
	 * 스킵). 실패는 격리해 스윕 결과에 영향을 주지 않는다(아카이브 잡들과 같은 격리 패턴).
	 */
	private void backfillAdDisclosuresSafely() {
		if (!adDisclosureEnabled) {
			log.debug("광고 판정 백필 비활성 — enabled=false");
			return;
		}
		try {
			AdDisclosureJudgeService.BackfillOutcome outcome = adJudge.backfillUnjudged();
			log.info("광고 판정 백필 — 잔여 {}건 중 {}건 처리", outcome.remaining(), outcome.processed());
		} catch (RuntimeException e) {
			log.warn("광고 판정 백필 실패(격리, 스윕 결과에는 영향 없음): {}", e.toString());
		}
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

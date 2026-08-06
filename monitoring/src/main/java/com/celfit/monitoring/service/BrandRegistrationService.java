package com.celfit.monitoring.service;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 브랜드 등록/탈퇴(태그 스펙 §1·§5) — 가입 = 추적 자동 시작, 탈퇴(CLOSED)까지 지속.
 * 동기 구간은 프로필 1콜뿐(존재·공개 검증 + pk·팔로워·biography) — 백필(~160콜, 수 분)은
 * was 동기 예산(10초) 밖 전용 executor에서 돈다. 백필 실패·앱 재시작으로 끊겨도
 * last_tracked_on이 null로 남아 다음 스윕이 트래킹(=같은 깊이)으로 백스톱한다.
 */
@Service
public class BrandRegistrationService {

	private static final Logger log = LoggerFactory.getLogger(BrandRegistrationService.class);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	/** 등록 결과 — replayed는 HTTP 코드(201/200) 결정용(RegistrationService.Result 관용구). */
	public record Result(long brandId, String username, Long followers, boolean replayed) {}

	/** 탈퇴 결과 — CLOSED·ALREADY_CLOSED는 멱등 204, NOT_FOUND는 404(was 재시도 안전). */
	public enum DeregisterOutcome { CLOSED, ALREADY_CLOSED, NOT_FOUND }

	private final HikerClient hiker;
	private final BrandRepository brands;
	private final BrandCollectService collect;
	private final Executor backfill;

	public BrandRegistrationService(HikerClient hiker, BrandRepository brands,
			BrandCollectService collect, @Qualifier("brandBackfillExecutor") Executor backfill) {
		this.hiker = hiker;
		this.brands = brands;
		this.collect = collect;
		this.backfill = backfill;
	}

	/**
	 * 등록 — 활성 기존 행이면 replay(Hiker 콜 0 — was 재시도가 중복 등록을 만들지 않게 한다).
	 * 프로필 콜이 계정 부재·비공개를 던지면 brand_account 행을 아예 만들지 않는다
	 * (RegistrationService "수집이 먼저다" 관용구 — 예외는 ApiExceptionHandler가 매핑).
	 */
	public Result register(String username) {
		if (username == null || username.isBlank()) {
			throw new ValidationException("username은 필수다");
		}
		String normalized = username.strip();
		var existing = brands.findByUsername(normalized);
		if (existing.isPresent() && existing.get().status() == BrandStatus.ACTIVE) {
			return new Result(existing.get().id(), normalized, null, true);
		}
		ProfileInfo profile = hiker.fetchProfile(normalized);
		long id = brands.insertOrReactivate(normalized, profile);
		BrandRow row = brands.findByUsername(normalized).orElseThrow();
		backfill.execute(() -> runBackfillSafely(row));
		return new Result(id, normalized, profile.followers(), false);
	}

	/** 백필 = 매일 스윕과 같은 코드(깊이·컷 규칙 동일 — 스펙 §4 정합). 실패는 격리 — 다음 스윕이 백스톱. */
	private void runBackfillSafely(BrandRow row) {
		try {
			collect.sweep(row);
			brands.touchSwept(row.id(), LocalDate.now(KST));
		} catch (RuntimeException e) {
			log.warn("브랜드 등록 백필 실패(격리) — {} 다음 스윕이 백스톱: {}", row.username(), e.toString());
			// was 폴링 계약(§5-2) — collecting에서 빠져나올 신호. 다음 스윕 성공(touchSwept)이 클리어한다.
			brands.markBackfillError(row.id(), "초기 수집에 실패했어요. 자동으로 재시도 중이에요.");
		}
	}

	public DeregisterOutcome deregister(String username) {
		if (brands.close(username)) {
			return DeregisterOutcome.CLOSED;
		}
		return brands.findByUsername(username).isPresent()
				? DeregisterOutcome.ALREADY_CLOSED : DeregisterOutcome.NOT_FOUND;
	}
}

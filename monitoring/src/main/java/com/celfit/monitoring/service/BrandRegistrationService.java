package com.celfit.monitoring.service;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.store.BrandCallCountRepository;
import com.celfit.monitoring.store.BrandHashtagRepository;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 브랜드 등록/탈퇴(태그 스펙 §1·§5) — 가입 = 추적 자동 시작, 탈퇴(CLOSED)까지 지속.
 * 동기 구간은 프로필 1콜뿐(존재·공개 검증 + pk·팔로워·biography) — 백필은 was 동기 예산(10초)
 * 밖 전용 executor에서 2단계로 돈다(단계식 ready — 2026-08-07 결정):
 *
 * <ul>
 *   <li><b>core</b>(backfill executor): 열거+적재 → 즉시 touchSwept — 등록 후 ~1~2분에 was가
 *       ready로 전환돼 게시물 목록이 뜬다(크롤링 정책 v1로 백필이 90일 → 365일이 되며 열거가
 *       ~6콜 → <b>~41콜</b>로 늘어난 값 — cclime 태그 847건 실측 기준. 정책 v1 이전 서술 "~30초"는
 *       폐기). backfill executor는 단일 스레드라 연속 등록은 계정당 그 속도로 줄을 선다. 그래도
 *       분리 효과는 그대로다(운영 실측: 구 단일 체인은 8분+ — 그중 ~85%가 목록 렌더에 필수
 *       아닌 보강 콜, 나머지가 앞 계정 대기).</li>
 *   <li><b>enrichment</b>(enrich executor): 게시자 프로필+댓글 수십 콜 — 별도 큐라 연속 등록
 *       때 뒤 계정 core가 앞 계정 보강을 기다리지 않는다. 실패는 로그만(ready 유지) — 다음
 *       스윕이 게시자 stale·댓글 워터마크로 백스톱한다.</li>
 * </ul>
 *
 * <p>core 실패·앱 재시작으로 끊겨도 last_swept_on이 null로 남아 다음 스윕이 백스톱한다.
 * 두 executor 모두 단일 스레드(브랜드 단위 큐잉·순서 보장). Hiker 콜 병렬화는 enrich 내부
 * 워커 풀이 담당 — 전역 동시 콜 최대 8(= 워커 6 + 스윕 core 1 + 등록 core 1, 스윕과 등록이
 * 겹치는 최악의 경우. 실측 무저항 한계 8과 동치 — BrandBackfillConfig 참조).
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
	private final BrandCallCountRepository callCounts;
	private final BrandHashtagRepository hashtags;
	private final BrandHashtagCollectService hashtagCollect;
	private final Executor backfill;
	private final Executor enrich;

	public BrandRegistrationService(HikerClient hiker, BrandRepository brands,
			BrandCollectService collect, BrandCallCountRepository callCounts,
			BrandHashtagRepository hashtags, BrandHashtagCollectService hashtagCollect,
			@Qualifier("brandBackfillExecutor") Executor backfill,
			@Qualifier("brandEnrichExecutor") Executor enrich) {
		this.hiker = hiker;
		this.brands = brands;
		this.collect = collect;
		this.callCounts = callCounts;
		this.hashtags = hashtags;
		this.hashtagCollect = hashtagCollect;
		this.backfill = backfill;
		this.enrich = enrich;
	}

	/** 기존 단일 인자 호출부용 위임 — brandName 미상(대행사 등록 등)은 계정명 유도 2종 태그만 시드한다. */
	public Result register(String username) {
		return register(username, null);
	}

	/**
	 * 등록 — 활성 기존 행이면 replay(Hiker 콜 0 — was 재시도가 중복 등록을 만들지 않게 한다).
	 * 프로필 콜이 계정 부재·비공개를 던지면 brand_account 행을 아예 만들지 않는다
	 * (RegistrationService "수집이 먼저다" 관용구 — 예외는 ApiExceptionHandler가 매핑).
	 *
	 * <p>replay 경로에도 해시태그를 시드한다(스펙 §2) — 대행사가 브랜드명 없이 먼저 등록한 뒤
	 * brand 유형 유저가 뒤늦게 같은 계정에 연결하면, 이번 호출의 brandName이 태그 셋에
	 * 유니온된다(insertTags는 ON CONFLICT DO NOTHING이라 멱등).
	 */
	public Result register(String username, String brandName) {
		if (username == null || username.isBlank()) {
			throw new ValidationException("username은 필수다");
		}
		String normalized = username.strip();
		var existing = brands.findByUsername(normalized);
		if (existing.isPresent() && existing.get().status() == BrandStatus.ACTIVE) {
			seedHashtagsSafely(existing.get().id(), normalized, brandName);
			return new Result(existing.get().id(), normalized, null, true);
		}
		ProfileInfo profile = hiker.fetchProfile(normalized);
		long id = brands.insertOrReactivate(normalized, profile);
		// 등록 검증 프로필 1콜의 사후 계상 — 콜 시점엔 brand_id가 없어 컨텍스트 스코프를 못 쓴다.
		// 등록 실패(계정 부재·비공개) 콜은 귀속할 브랜드가 없어 미집계다(어드민 크롤링 비용 설계).
		callCounts.add(id, LocalDate.now(KST), 1);
		BrandRow row = brands.findByUsername(normalized).orElseThrow();
		seedHashtagsSafely(id, normalized, brandName);
		backfill.execute(() -> runBackfillSafely(row));
		return new Result(id, normalized, profile.followers(), false);
	}

	/**
	 * 태그 3종(브랜드명 미상 시 2종) + 기본 제외 문자열(계정명 루트) 시드 — 둘 다 멱등 삽입.
	 * insertOrReactivate(이미 커밋됨)와 backfill.execute 사이 지점이라 실패를 격리한다 — 여기서
	 * 던지면 백필이 영구 미예약되는데, 재시도는 replay 분기를 타서 복구할 수 없다(신규 등록
	 * 자체는 이미 끝난 상태). 시드 실패의 실피해는 "해시태그 스윕이 태그 없음으로 조용히
	 * 스킵"뿐이고 다음 replay 재등록이 재시드하므로, 등록·백필을 막지 않는 warn 격리가 맞다.
	 */
	private void seedHashtagsSafely(long brandId, String username, String brandName) {
		try {
			hashtags.insertTags(brandId, BrandHashtagTags.derive(brandName, username));
			hashtags.insertDefaultExclusion(brandId, BrandHashtagTags.root(username));
		} catch (RuntimeException e) {
			log.warn("브랜드 해시태그 시드 실패(격리) — {} 다음 재등록이 재시드: {}", username, e.toString());
		}
	}

	/**
	 * 백필 core = 매일 스윕과 같은 열거·적재 코드(깊이·컷 규칙 동일 — 스펙 §4 정합). 성공 즉시
	 * touchSwept(ready 전환) 후 보강을 전용 executor로 넘긴다. core 실패는 격리 — 보강도 예약하지
	 * 않는다(게시물 없이 보강만 도는 낭비 방지, 다음 스윕이 전체를 백스톱).
	 */
	private void runBackfillSafely(BrandRow row) {
		try {
			List<PostInfo> posts = collect.sweepCore(row);
			brands.touchSwept(row.id(), LocalDate.now(KST));
			enrich.execute(() -> {
				runEnrichSafely(row, posts);
				runHashtagBackfillSafely(row);
			});
		} catch (RuntimeException e) {
			log.warn("브랜드 등록 백필 실패(격리) — {} 다음 스윕이 백스톱: {}", row.username(), e.toString());
			// was 폴링 계약(§5-2) — collecting에서 빠져나올 신호. 다음 스윕 성공(touchSwept)이 클리어한다.
			brands.markBackfillError(row.id(), "초기 수집에 실패했어요. 자동으로 재시도 중이에요.");
		}
	}

	/**
	 * 보강 실패는 backfill_error를 남기지 않는다 — 목록·지표는 이미 서빙 중(ready)이라 "초기 수집
	 * 실패" 문구가 오히려 오보고, 미수집분(게시자 stale·댓글 워터마크)은 다음 스윕이 자동 재시도한다.
	 */
	private void runEnrichSafely(BrandRow row, List<PostInfo> posts) {
		try {
			collect.enrich(row, posts);
		} catch (RuntimeException e) {
			log.warn("브랜드 등록 보강 실패(격리) — {} 다음 스윕이 백스톱: {}", row.username(), e.toString());
		}
	}

	/**
	 * 등록 시 해시태그 첫 스윕 — 보강 뒤에 돌아 ready(~30초)에 영향 0. core는 이미 성공했으므로
	 * 여기 실패는 backfill_error를 남기지 않는다(warn 로그만) — 다음 일일 스윕이 백스톱한다.
	 */
	private void runHashtagBackfillSafely(BrandRow row) {
		try {
			hashtagCollect.sweep(row);
		} catch (RuntimeException e) {
			log.warn("브랜드 등록 해시태그 백필 실패(격리) — {} 다음 스윕이 백스톱: {}", row.username(), e.toString());
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

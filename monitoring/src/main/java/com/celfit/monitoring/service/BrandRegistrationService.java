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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 *       ready로 전환돼 게시물 목록이 뜬다(크롤링 정책 v1로 백필이 90일 → 브랜드별 수집 창
 *       (collection_months, 기본 12개월)이 되며 열거가 ~6콜 → <b>~41콜</b>로 늘어난 값 — 12개월
 *       창의 cclime 태그 847건 실측 기준. 수집 창이 짧으면 그만큼 준다. 정책 v1 이전 서술
 *       "~30초"는 폐기). backfill executor는 동시 2스레드(08-12) — 연속 등록 시 뒤 계정이 앞
 *       계정 완주를 기다리는 줄이 절반이다. 그래도 분리 효과는 그대로다(운영 실측: 구 단일
 *       체인은 8분+ — 그중 ~85%가 목록 렌더에 필수 아닌 보강 콜, 나머지가 앞 계정 대기).
 *       2026-08-12 스트리밍 개정: 적재는 페이지 단위 즉시, 서빙 창(30일) 커버 시 markServing으로
 *       ready가 완주보다 먼저 열린다 — tooq.official 실측 8분 24초 → 서빙 창 커버 ~1분 30초.</li>
 *   <li><b>enrichment</b>(enrich executor): 게시자 프로필+댓글 수십 콜 — 별도 큐라 연속 등록
 *       때 뒤 계정 core가 앞 계정 보강을 기다리지 않는다. 실패는 로그만(ready 유지) — 다음
 *       스윕이 게시자 stale·댓글 워터마크로 백스톱한다.</li>
 * </ul>
 *
 * <p>core 실패·앱 재시작으로 끊겨도 last_swept_on이 null로 남아 다음 스윕이 백스톱한다.
 * backfill은 동시 2스레드(브랜드 단위 태스크라 브랜드 안 순서는 유지), enrich는 단일 스레드.
 * Hiker 콜 병렬화는 enrich 내부 워커 풀이 담당 — 전역 동시 콜 최대 9(= 워커 6 + 스윕 core 1 +
 * 등록 core 2, 스윕과 등록이 겹치는 최악의 경우. 08-12 램프 실측 안전 구간 ~10 이내 —
 * BrandBackfillConfig 참조).
 */
@Service
public class BrandRegistrationService {

	private static final Logger log = LoggerFactory.getLogger(BrandRegistrationService.class);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	/** 수집 창 값 공간(collectionMonths 스펙 §2) — DB CHECK 제약과 같은 집합이다. */
	private static final Set<Integer> ALLOWED_MONTHS = Set.of(1, 3, 6, 12);
	private static final int DEFAULT_MONTHS = 12;

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

	/**
	 * 기존 단일 인자 호출부용 위임 — brandName 미상(대행사 등록 등)은 계정명 유도 2종 태그만
	 * 시드하고, collectionMonths 미상은 기본 12개월로 접는다.
	 */
	public Result register(String username) {
		return register(username, null, null);
	}

	/** 기존 2인자 호출부용 위임 — collectionMonths 미상은 기본 12개월. */
	public Result register(String username, String brandName) {
		return register(username, brandName, null);
	}

	/**
	 * 등록 — 활성 기존 행이면 replay(Hiker 콜 0 — was 재시도가 중복 등록을 만들지 않게 한다).
	 * 프로필 콜이 계정 부재·비공개를 던지면 brand_account 행을 아예 만들지 않는다
	 * (RegistrationService "수집이 먼저다" 관용구 — 예외는 ApiExceptionHandler가 매핑).
	 *
	 * <p>replay 경로에도 해시태그를 시드한다(스펙 §2) — 대행사가 브랜드명 없이 먼저 등록한 뒤
	 * brand 유형 유저가 뒤늦게 같은 계정에 연결하면, 이번 호출의 brandName이 태그 셋에
	 * 유니온된다(insertTags는 ON CONFLICT DO NOTHING이라 멱등).
	 *
	 * <p>replay 경로에서 요청 collectionMonths가 기존 창보다 크면 기간 확장(expandIfRequested)까지
	 * 수행한다 — 재등록이 창 상향의 유일한 입구다(별도 API 없음).
	 */
	public Result register(String username, String brandName, Integer collectionMonths) {
		if (username == null || username.isBlank()) {
			throw new ValidationException("username은 필수다");
		}
		int months = collectionMonths == null ? DEFAULT_MONTHS : collectionMonths;
		// 검증은 저장 도달 전에 — 값 공간 밖이 내려가면 CHECK 위반이 500으로 샌다(was 400과 이중 방어).
		if (!ALLOWED_MONTHS.contains(months)) {
			throw new ValidationException("collectionMonths는 1|3|6|12만 허용한다");
		}
		String normalized = username.strip();
		var existing = brands.findByUsername(normalized);
		if (existing.isPresent() && existing.get().status() == BrandStatus.ACTIVE) {
			seedHashtagsSafely(existing.get().id(), normalized, brandName);
			expandIfRequested(existing.get(), months);
			return new Result(existing.get().id(), normalized, null, true);
		}
		ProfileInfo profile = hiker.fetchProfile(normalized);
		long id = brands.insertOrReactivate(normalized, profile, months);
		// 등록 검증 프로필 1콜의 사후 계상 — 콜 시점엔 brand_id가 없어 컨텍스트 스코프를 못 쓴다.
		// 등록 실패(계정 부재·비공개) 콜은 귀속할 브랜드가 없어 미집계다(어드민 크롤링 비용 설계).
		callCounts.add(id, LocalDate.now(KST), 1);
		BrandRow row = brands.findByUsername(normalized).orElseThrow();
		seedHashtagsSafely(id, normalized, brandName);
		backfill.execute(() -> runBackfillSafely(row));
		return new Result(id, normalized, profile.followers(), false);
	}

	/**
	 * 기간 확장(collectionMonths 스펙 §3) — 자산 창보다 클 때만. 창 상향과 last_swept_on 클리어를
	 * 한 UPDATE(expandWindow)로 끝내고 백필을 재제출한다. 재제출이 죽어도 last_swept_on null이라
	 * 다음 새벽 스윕이 전체 창을 다시 연다(등록 백필과 같은 백스톱 규율). 열거는 최신부터 커서
	 * 단방향이라 "새 컷까지 재열거"가 증분 수집의 실체다 — 기지 게시물은 insert 스킵(멱등 upsert).
	 * 축소는 무시한다(수집된 사실이 정본 — 요청서 §4).
	 */
	private void expandIfRequested(BrandRow existing, int months) {
		if (months <= existing.collectionMonths()) {
			return;
		}
		brands.expandWindow(existing.id(), months);
		BrandRow row = brands.findByUsername(existing.username()).orElseThrow();
		backfill.execute(() -> runBackfillSafely(row));
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
	 * 백필 core = 매일 스윕과 같은 열거·적재 코드(스트리밍 — 페이지마다 즉시 적재). 서빙 창(30일)
	 * 커버 콜백에서 markServing(FE ready만 당김 — last_swept_on은 완주 touchSwept 몫, 스펙 §1)과
	 * 선행 보강(그때까지 적재분의 게시자·댓글)을 수행하고, 완주 후엔 잔여분만 보강한다. 선행분
	 * 코드 집합으로 잔여를 걸러 이중 보강 콜을 막는다(게시자 fresh 캐시·댓글 워터마크가 있어
	 * 겹쳐도 안전하지만 헛 게이트 조회를 줄인다). core 실패는 격리 — 이미 적재된 페이지는 서빙
	 * 유지, 잔여 보강도 예약하지 않는다(다음 스윕이 전체를 백스톱).
	 */
	private void runBackfillSafely(BrandRow row) {
		try {
			Set<String> earlyCodes = new HashSet<>();
			List<PostInfo> posts = collect.sweepCore(row, early -> {
				brands.markServing(row.id());
				early.forEach(p -> earlyCodes.add(p.shortCode()));
				if (!early.isEmpty()) {
					enrich.execute(() -> runEnrichSafely(row, early));
				}
			});
			brands.touchSwept(row.id(), LocalDate.now(KST));
			List<PostInfo> remainder = posts.stream()
					.filter(p -> !earlyCodes.contains(p.shortCode())).toList();
			enrich.execute(() -> {
				runEnrichSafely(row, remainder);
				runHashtagBackfillSafely(row);
			});
		} catch (RuntimeException e) {
			log.warn("브랜드 등록 백필 실패(격리) — {} 다음 스윕이 백스톱: {}", row.username(), e.toString());
			// was 폴링 계약(§5-2) — collecting에서 빠져나올 신호. 다음 스윕 성공(touchSwept)이 클리어한다.
			// markServing 이후 실패면 ready가 이미 열려 있고(부분 데이터 서빙) 이 문구는 FE에서 무시된다.
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

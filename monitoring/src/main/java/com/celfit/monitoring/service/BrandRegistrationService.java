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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 브랜드 등록/탈퇴(태그 스펙 §1·§5) — 가입 = 추적 자동 시작, 탈퇴(CLOSED)까지 지속.
 * 동기 구간은 프로필 1콜뿐(존재·공개 검증 + pk·팔로워·biography) — 백필은 was 동기 예산(10초)
 * 밖 전용 executor에서 2단계로 돈다(2026-08-13 완결 배치 서빙 개정 — 구 "단계식 ready"의
 * 서빙 창(30일) 커버 기준은 폐기):
 *
 * <ul>
 *   <li><b>core</b>(backfill executor): 열거+적재를 페이지 단위로 하면서 페이지마다 그 페이지분을
 *       enrich 큐에 넘긴다(크롤링 정책 v1로 백필 깊이가 브랜드별 수집 창(collection_months, 기본
 *       12개월)이라 열거가 ~41콜 — 12개월 창의 cclime 태그 847건 실측 기준. 수집 창이 짧으면 그만큼
 *       준다). backfill executor는 동시 2스레드(08-12) — 연속 등록 시 뒤 계정이 앞 계정 완주를
 *       기다리는 줄이 절반이다(운영 실측: 구 단일 체인은 8분+ — 그중 ~85%가 보강 콜).</li>
 *   <li><b>enrichment</b>(enrich executor): 게시자 프로필+댓글 수십 콜 — 별도 큐라 열거가 앞서
 *       달리고 보강이 뒤에서 겹쳐 돈다. 실패는 로그만(ready 유지) — 다음 스윕이 게시자 stale·
 *       댓글 워터마크로 백스톱한다.</li>
 * </ul>
 *
 * <p>ready(markServing)는 <b>첫 페이지분 보강이 끝나는 지점</b>에서 열리고, 완주 표식
 * (touchSwept = 응답 collectionCompletedAt · FE 폴링 종료 조건)은 <b>모든 페이지 보강 뒤</b>에
 * 찍힌다 — 목록에는 정산된 페이지만 오른다(스펙 §1·§2, {@link #runBackfillSafely} 참조).
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
	 *
	 * <p>여기 in-memory 게이트는 불필요한 UPDATE를 줄이는 사전 컷일 뿐이고, 축소 차단의 정본은
	 * expandWindow의 조건부 UPDATE다. 그 UPDATE가 false(0행)면 동시 요청이 더 큰 창으로 이미
	 * 이겼다는 뜻이고 그쪽이 백필도 제출했으므로, 같은 창을 두 번 여는 재제출을 건너뛴다.
	 */
	private void expandIfRequested(BrandRow existing, int months) {
		if (months <= existing.collectionMonths()) {
			return;
		}
		if (!brands.expandWindow(existing.id(), months)) {
			return;
		}
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
	 * 백필 core = 매일 스윕과 같은 열거·적재 코드(페이지 스트리밍). 2026-08-13 개정: 페이지마다
	 * 그 페이지분을 <b>enrich 큐에 제출</b>하고 열거는 계속 앞서 달린다(파이프라인 — 열거 ~5초/페이지와
	 * 보강 ~5.4초/페이지가 겹쳐 완주가 절반이 된다). <b>첫 제출분의 보강이 끝나는 지점에서
	 * markServing</b>으로 FE ready를 연다(구 "서빙 창 30일 커버" 기준 대체) — 목록에 오르는 건
	 * 정산된 페이지뿐이라 반쯤 채워진 카드가 뜨지 않는다.
	 *
	 * <p>touchSwept는 <b>모든 페이지 보강이 끝난 뒤</b>에 찍는다 — 이 값이 곧 응답
	 * collectionCompletedAt이고 FE의 폴링 종료 조건이라, 아직 정산 안 된 페이지가 남은 채로 찍으면
	 * FE가 미완성 목록을 최종본으로 알고 폴링을 멈춘다. 열거 완주 ≠ 수집 완주로 의미가 갈렸다.
	 * "열거가 끝났으니 여기서 찍자"로 되돌리면 그 회귀가 그대로 재현된다.
	 *
	 * <p>페이지 태스크는 enrich executor에서 돌고 여기(backfill executor 스레드)에서 join으로
	 * 기다린다 — 두 층이 <b>별도 풀</b>이어야 한다(합치면 영구 자기 교착 — BrandBackfillConfig 참조).
	 * core 스레드가 join에 묶이는 건 <b>의도한 설계다</b>: thenRun 체이닝으로 풀어도 enrich가 전역
	 * 단일 스레드라 대기가 core 스레드에서 큐로 옮겨갈 뿐 빨라지지 않고, 이 블로킹이 유일한
	 * <b>브랜드 간 백프레셔</b>라 없애면 등록 폭주 시 N개 브랜드가 앞다퉈 열거하며 무제한 단일 스레드
	 * 큐에 페이지 목록을 쏟아붓는다(08-12 OOM과 같은 형태).
	 * 콜백이 페이지분만 주므로 페이지끼리 겹치지 않아 중복 필터(구 earlyCodes)가 필요 없다.
	 *
	 * <p>core 실패는 격리 — 이미 정산된 페이지는 서빙을 유지하고, 다음 스윕이 잔여를 백스톱한다.
	 */
	private void runBackfillSafely(BrandRow row) {
		try {
			AtomicBoolean served = new AtomicBoolean();
			List<CompletableFuture<Void>> pages = new ArrayList<>();
			collect.sweepCore(row, page -> pages.add(CompletableFuture.runAsync(() -> {
				runEnrichSafely(row, page);
				// 첫 완료분이 ready를 연다. 페이지 순서가 아니라 완료 순서인 것은 무해하다 —
				// 목록 정렬은 taken_at이고 markServing은 last_swept_at IS NULL 가드로 1회만 먹는다.
				if (served.compareAndSet(false, true)) {
					brands.markServing(row.id());
				}
			}, enrich)));
			CompletableFuture.allOf(pages.toArray(CompletableFuture[]::new)).join();
			brands.touchSwept(row.id(), LocalDate.now(KST));
			enrich.execute(() -> runHashtagBackfillSafely(row));
		} catch (RuntimeException e) {
			log.warn("브랜드 등록 백필 실패(격리) — {} 다음 스윕이 백스톱: {}", row.username(), e.toString());
			// was 폴링 계약(§5-2) — collecting에서 빠져나올 신호. 다음 스윕 성공(touchSwept)이 클리어한다.
			// markServing 이후 실패면 ready가 이미 열려 있고(정산된 페이지 서빙) 이 문구는 FE에서 무시된다.
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
	 * 등록 시 해시태그 첫 스윕 — 전 페이지 보강 뒤 꼬리라 ready(첫 배치 완결)에 영향 0. core는 이미 성공했으므로
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

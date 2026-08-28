package com.celfit.monitoring.service;

import com.celfit.monitoring.domain.BrandStatus;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.store.BrandCallCountRepository;
import com.celfit.monitoring.store.BrandRepository;
import com.celfit.monitoring.store.BrandRow;
import com.celfit.monitoring.store.TaggedPostRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
 * <p>ready(markServing)는 <b>첫 페이지의 게시자 보강이 끝나는 지점</b>에서 열리고(2026-08-18
 * 계정 게이트 단축 — 댓글 수집·광고 표기 판정은 기다리지 않는다), 완주 표식(touchSwept = 응답
 * collectionCompletedAt · FE 폴링 종료 조건)은 <b>모든 페이지 보강(댓글·판정 포함) 뒤</b>에
 * 찍힌다 — 목록에는 정산된 페이지만 오른다(스펙 §1·§2, {@link #runBackfillSafely} 참조).
 *
 * <p>core 실패·앱 재시작으로 끊겨도 last_swept_on이 null로 남아 다음 스윕이 백스톱한다.
 * backfill은 동시 2스레드(브랜드 단위 태스크라 브랜드 안 순서는 유지), enrich는 전역 공유 풀
 * 2스레드({@code monitoring.brand.enrich-executor-concurrency}, 08-13 — 백필 core 2병렬과 짝).
 * 해시태그 스윕(태그 등록 직후·replay 재등록 트리거)은 08-18부터 별도 hashtagSweep executor(기본
 * 1스레드)에서 돈다 — LLM 판정의 느린 외부 콜이 이 enrich 풀을 점유해 뒤 계정 백필을 지연시킨
 * 운영 사고 후속. Hiker 콜 병렬화는 enrich 내부 워커 풀이 담당 — 전역 동시 콜 최대 14(= 워커 10 +
 * 스윕 core 1 + 등록 core 2 + 해시태그 스윕 1, 전부 겹치는 최악의 경우. BrandBackfillConfig 참조).
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
	private final BrandHashtagCollectService hashtagCollect;
	private final TaggedPostRepository taggedPosts;
	/** 수집 개수 상한 — BrandCollectService와 같은 키. 0 이하는 무제한(= 확장 스킵 비활성). */
	private final int collectionPostLimit;
	private final Executor backfill;
	private final Executor enrich;
	private final Executor hashtagSweep;

	public BrandRegistrationService(HikerClient hiker, BrandRepository brands,
			BrandCollectService collect, BrandCallCountRepository callCounts,
			BrandHashtagCollectService hashtagCollect,
			TaggedPostRepository taggedPosts,
			@Value("${monitoring.brand.collection-post-limit:2000}") int collectionPostLimit,
			@Qualifier("brandBackfillExecutor") Executor backfill,
			@Qualifier("brandEnrichExecutor") Executor enrich,
			@Qualifier("brandHashtagSweepExecutor") Executor hashtagSweep) {
		this.hiker = hiker;
		this.brands = brands;
		this.collect = collect;
		this.callCounts = callCounts;
		this.hashtagCollect = hashtagCollect;
		this.taggedPosts = taggedPosts;
		this.collectionPostLimit = collectionPostLimit;
		this.backfill = backfill;
		this.enrich = enrich;
		this.hashtagSweep = hashtagSweep;
	}

	/** competitor 계정 타입 리터럴(was BrandAccountType.COMPETITOR와 동형) — 이 값 외엔 전부 own 취급. */
	private static final String ACCOUNT_TYPE_COMPETITOR = "competitor";

	/**
	 * 기존 단일 인자 호출부용 위임 — brandName은 더 이상 시드에 쓰이지 않는다(2026-08-28부터 태그
	 * 시드 자체가 monitoring 책임이 아니다 — was가 링크 생성 시 유도 태그를 일반 태그 add로
	 * push한다, {@link #register(String, String, Integer, String)} 참조). collectionMonths 미상은
	 * 기본 12개월로 접는다. accountType 미상은 own(2026-08-19 경쟁사 판정 제거 설계).
	 */
	public Result register(String username) {
		return register(username, null, null, null);
	}

	/** 기존 2인자 호출부용 위임 — collectionMonths·accountType 미상. */
	public Result register(String username, String brandName) {
		return register(username, brandName, null, null);
	}

	/** 기존 3인자 호출부용 위임(테스트·레거시) — accountType 미상(own). */
	public Result register(String username, String brandName, Integer collectionMonths) {
		return register(username, brandName, collectionMonths, null);
	}

	/**
	 * 등록 — 활성 기존 행이면 replay(Hiker 콜 0 — was 재시도가 중복 등록을 만들지 않게 한다).
	 * 프로필 콜이 계정 부재·비공개를 던지면 brand_account 행을 아예 만들지 않는다
	 * (RegistrationService "수집이 먼저다" 관용구 — 예외는 ApiExceptionHandler가 매핑).
	 *
	 * <p>replay 경로에서도 즉시 스윕을 트리거한다(2026-08-17). replay는 신규 등록과 달리 백필이
	 * 돌지 않아(hiker 콜 0이 replay의 존재 이유) 예전엔 재등록 시점의 즉시 조회가 없었다 —
	 * "해시태그를 등록한 당시에 조회해서 당일 게시물을 즉시 추가한다"는 합의된 동작을 replay
	 * 경로에도 채운다({@link #triggerHashtagSweep} 참조). <b>태그 시드 자체는 2026-08-28부터
	 * monitoring이 하지 않는다</b> — 태그 생성 권한이 was로 일원화돼(was가 링크 생성 시 유도
	 * 태그를 일반 태그 add로 push), 여기서는 이미 존재하는 태그로 스윕만 돌린다.
	 *
	 * <p>replay 경로에서 요청 collectionMonths가 기존 창보다 크면 기간 확장(expandIfRequested)까지
	 * 수행한다 — 재등록이 창 상향의 유일한 입구다(별도 API 없음).
	 *
	 * <p>accountType(nullable, 기본 own — 2026-08-19 경쟁사 판정 제거 설계 §2)은 has_own_link 초기화·
	 * 승격에만 쓰인다: 신규 삽입이면 {@code accountType != 'competitor'} 그대로 심고, 기존 행
	 * replay·재활성이면 own일 때만 승격한다(경쟁사 재등록이 다른 유저의 own 연결을 false로 내리면
	 * 안 된다 — {@link com.celfit.monitoring.store.BrandRepository#insertOrReactivate} 참조).
	 */
	public Result register(String username, String brandName, Integer collectionMonths, String accountType) {
		long startNanos = System.nanoTime();
		if (username == null || username.isBlank()) {
			throw new ValidationException("username은 필수다");
		}
		int months = collectionMonths == null ? DEFAULT_MONTHS : collectionMonths;
		// 검증은 저장 도달 전에 — 값 공간 밖이 내려가면 CHECK 위반이 500으로 샌다(was 400과 이중 방어).
		if (!ALLOWED_MONTHS.contains(months)) {
			throw new ValidationException("collectionMonths는 1|3|6|12만 허용한다");
		}
		boolean ownRequest = !ACCOUNT_TYPE_COMPETITOR.equals(accountType);
		String normalized = username.strip();
		var existing = brands.findByUsername(normalized);
		if (existing.isPresent() && existing.get().status() == BrandStatus.ACTIVE) {
			triggerHashtagSweep(existing.get());
			expandIfRequested(existing.get(), months);
			if (ownRequest && !existing.get().hasOwnLink()) {
				brands.setHasOwnLink(normalized, true);
			}
			logRegistered(normalized, startNanos, true);
			return new Result(existing.get().id(), normalized, null, true);
		}
		ProfileInfo profile = hiker.fetchProfile(normalized);
		long id = brands.insertOrReactivate(normalized, profile, months, ownRequest);
		// 등록 검증 프로필 1콜의 사후 계상 — 콜 시점엔 brand_id가 없어 컨텍스트 스코프를 못 쓴다.
		// 등록 실패(계정 부재·비공개) 콜은 귀속할 브랜드가 없어 미집계다(어드민 크롤링 비용 설계).
		callCounts.add(id, LocalDate.now(KST), 1);
		BrandRow row = brands.findByUsername(normalized).orElseThrow();
		backfill.execute(() -> runBackfillSafely(row));
		logRegistered(normalized, startNanos, false);
		return new Result(id, normalized, profile.followers(), false);
	}

	/**
	 * 등록 완료 로그 — 그라파나 '등록 초기 응답' 패널(브랜드 운영 건강)이 Loki에서 이 줄을
	 * {@code username=... durationMs=...} 정규식으로 파싱한다. 포맷을 바꾸면 패널 쿼리도 함께
	 * 바꿀 것. 등록 소요를 DB에 남기지 않는 대신 로그가 브랜드별 개별 기록의 정본이다.
	 */
	private void logRegistered(String username, long startNanos, boolean replayed) {
		long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
		log.info("브랜드 등록 완료 username={} durationMs={} replayed={}", username, durationMs, replayed);
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
	 *
	 * <p>2026-08-19 수집 상한 v2(§7-2): 재백필의 컷이 기존 창 안에 떨어지는 브랜드는 확장 스킵 —
	 * 아래 참조.
	 */
	private void expandIfRequested(BrandRow existing, int months) {
		if (months <= existing.collectionMonths()) {
			return;
		}
		// 확장 스킵(스펙 §7-2) — 재백필의 컷이 "기존 창 안"에 떨어질 때만 스킵한다: 그러면 확장
		// 구간(기존 창 밖)에는 한 건도 도달하지 못하므로 열거 전량이 낭비다(~96콜 절약). 창·커버리지
		// 마킹만 하고 수집 상태는 불변 — UI가 capped·covered_until로 "확장 신청·상한 도달"을 표시한다.
		//
		// 판정 입력은 limit번째 최신 태그 행의 taken_at = 재백필 컷의 정확한 예측치다(생애 누적 행
		// 수가 아니다 — nthNewestTagTakenAt javadoc의 오표기 사례 참조). 이 값이 그대로
		// covered_until 폴백이기도 하다: 근사가 아닌 실제 도달 깊이라 §7-4 컷 클램프도 정확해진다.
		if (collectionPostLimit > 0) {
			Instant existingCutoff = ZonedDateTime.now(KST).minusMonths(existing.collectionMonths()).toInstant();
			Optional<Instant> predictedCut = taggedPosts.nthNewestTagTakenAt(existing.id(), collectionPostLimit);
			if (predictedCut.isPresent() && predictedCut.get().isAfter(existingCutoff)) {
				brands.raiseWindowCapped(existing.id(), months, predictedCut.get());
				return;
			}
		}
		if (!brands.expandWindow(existing.id(), months)) {
			return;
		}
		BrandRow row = brands.findByUsername(existing.username()).orElseThrow();
		backfill.execute(() -> runBackfillSafely(row));
	}

	/**
	 * 백필 core = 매일 스윕과 같은 열거·적재 코드(페이지 스트리밍). 2026-08-13 개정: 페이지마다
	 * 그 페이지분을 <b>enrich 큐에 제출</b>하고 열거는 계속 앞서 달린다(파이프라인 — 열거 ~5초/페이지와
	 * 보강 ~5.4초/페이지가 겹쳐 완주가 절반이 된다). <b>첫 페이지의 게시자 보강이 끝나는 지점에서
	 * markServing</b>으로 FE ready를 연다(2026-08-18 계정 게이트 단축 — 구 "서빙 창 30일 커버" 기준
	 * 대체 이후, "첫 페이지 전체 보강(댓글·광고 판정 포함) 완료" 기준도 대체) — was 게시물 게이트
	 * (markEnriched)와 같은 시점이라 목록에는 정산된 페이지만 오르되, 댓글·판정(브랜드당 순차 join
	 * 특성상 수 초~수십 초)까지는 더 기다리지 않는다({@link BrandCollectService#enrich(BrandRow,
	 * List, Runnable)} 참조).
	 *
	 * <p>onVisible 훅은 <b>첫 페이지에만</b> 단다 — sweepCore 콜백은 이 브랜드 백필 태스크 안에서
	 * 단일 스레드로 순차 호출되므로({@code pages}가 비어 있는지로 "첫 페이지"를 판별해도 경합이
	 * 없다), 뒤 페이지는 훅 없이(null) 돈다. served CAS는 방어적 1회 보장(재가입 등으로 이 메서드가
	 * 다시 불려도 안전) — DB 쪽 IS NULL 가드(markServing)와 같은 이중 방어.
	 *
	 * <p>touchSwept는 <b>모든 페이지 보강이 끝난 뒤</b>에 찍는다 — 이 값이 곧 응답
	 * collectionCompletedAt이고 FE의 폴링 종료 조건이라, 아직 정산 안 된 페이지가 남은 채로 찍으면
	 * FE가 미완성 목록을 최종본으로 알고 폴링을 멈춘다. 열거 완주 ≠ 수집 완주로 의미가 갈렸다.
	 * "열거가 끝났으니 여기서 찍자"로 되돌리면 그 회귀가 그대로 재현된다.
	 *
	 * <p>페이지 태스크는 enrich executor에서 돌고 여기(backfill executor 스레드)에서 join으로
	 * 기다린다 — 두 층이 <b>별도 풀</b>이어야 한다(합치면 영구 자기 교착 — BrandBackfillConfig 참조).
	 * core 스레드가 join에 묶이는 건 <b>의도한 설계다</b>: thenRun 체이닝으로 풀어도 enrich가 전역
	 * 공유 풀(2스레드)이라 대기가 core 스레드에서 큐로 옮겨갈 뿐 빨라지지 않고, 이 블로킹이 유일한
	 * <b>브랜드 간 백프레셔</b>라 없애면 등록 폭주 시 N개 브랜드가 앞다퉈 열거하며 무제한 공유 큐에
	 * 페이지 목록을 쏟아붓는다(08-12 OOM과 같은 형태).
	 * 콜백이 페이지분만 주므로 페이지끼리 겹치지 않아 중복 필터(구 earlyCodes)가 필요 없다.
	 *
	 * <p>core 실패는 격리 — 이미 정산된 페이지는 서빙을 유지하고, 다음 스윕이 잔여를 백스톱한다.
	 */
	private void runBackfillSafely(BrandRow row) {
		try {
			AtomicBoolean served = new AtomicBoolean();
			List<CompletableFuture<Void>> pages = new ArrayList<>();
			collect.sweepCore(row, page -> {
				// 이 콜백 자체는 sweepCore 안에서 순차 호출된다 — pages.isEmpty()는 "아직 아무
				// 페이지도 제출 안 한 시점"을 경합 없이 가리킨다(= 이번이 첫 페이지).
				Runnable onVisible = pages.isEmpty()
						? () -> {
							if (served.compareAndSet(false, true)) {
								brands.markServing(row.id());
							}
						}
						: null;
				pages.add(CompletableFuture.runAsync(() -> runEnrichSafely(row, page, onVisible), enrich));
			});
			CompletableFuture.allOf(pages.toArray(CompletableFuture[]::new)).join();
			brands.touchSwept(row.id(), LocalDate.now(KST));
			triggerHashtagSweep(row);
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
	 *
	 * <p>onVisible은 그대로 {@link BrandCollectService#enrich(BrandRow, List, Runnable)}에 위임한다
	 * — markEnriched와 같은 finally 보장이라 여기서 별도로 재시도·대체 호출할 필요가 없다(ensureAuthors
	 * 하드 실패 경로 포함).
	 */
	private void runEnrichSafely(BrandRow row, List<PostInfo> posts, Runnable onVisible) {
		try {
			collect.enrich(row, posts, onVisible);
		} catch (RuntimeException e) {
			log.warn("브랜드 등록 보강 실패(격리) — {} 다음 스윕이 백스톱: {}", row.username(), e.toString());
		}
	}

	/**
	 * 태그 등록(PUT/POST hashtag-tags 성공) 직후 즉시 스윕 트리거(2026-08-17 FE 협의 — "해시태그를
	 * 등록한 당시에 조회해서 당일 게시물을 즉시 추가한다"). tags가 비어 있으면 아무 것도 하지
	 * 않는다 — hashtagCollect.sweep 자체도 태그 0건이면 콜 0으로 즉시 반환하지만, 여기서 먼저
	 * 걸러 불필요한 executor 제출 자체를 줄인다.
	 *
	 * <p>등록 백필·replay 재등록과 같은 트리거 경로에서 전용 hashtagSweep executor로 비동기 제출한다
	 * (동기 응답 지연 금지) — 실패는 격리(다음 야간 스윕이 백스톱). sweep은 ON CONFLICT DO NOTHING
	 * 기반이라 야간 스윕과 동시 실행돼도 데이터는 안전하다.
	 *
	 * <p>2026-08-18까지는 enrich executor에 얹었으나, LLM 브랜드 관련성 판정(콜당 수 초 + 429 백오프)이
	 * 빠른 Hiker 콜 위주인 보강 워커(2스레드)를 분 단위로 점유해 뒤 계정 백필을 지연시킨 운영 사고
	 * 후속으로 전용 executor로 분리했다({@link com.celfit.monitoring.config.BrandBackfillConfig}
	 * 참조).
	 *
	 * <p><b>초기 백필 미완 브랜드는 스킵한다</b>(2026-08-28 태그 생성 권한 was 일원화 후속) — 신규
	 * 등록 직후 was가 시드 태그를 push하는데, 초기 백필과 해시태그 스윕이 동시에 돌면 전역 콜
	 * 예산(14)을 경합한다. row.lastSweptOn()이 null이면 아직 backfill 꼬리({@link #runBackfillSafely}
	 * 끝의 {@link #triggerHashtagSweep})가 돌지 않은 상태이고, 그 꼬리가 곧 이 태그까지 쓸어주므로
	 * 여기서 또 스윕을 얹을 필요가 없다. 경합 없음: was의 push가 touchSwept <b>뒤</b>에 도착하면
	 * 이 가드를 통과해 정상 트리거되고, touchSwept <b>전</b>에 도착하면 뒤이은 백필 꼬리
	 * triggerHashtagSweep이 그 태그를 커버한다 — 순서 무관 안전.
	 */
	public void triggerHashtagSweepIfNonEmpty(BrandRow row, List<String> tags) {
		if (tags.isEmpty()) {
			return;
		}
		if (row.lastSweptOn() == null) {
			log.info("초기 백필 미완 — 태그 추가 즉시 스윕 스킵(백필 꼬리가 대신 처리) brandId={}, username={}",
					row.id(), row.username());
			return;
		}
		triggerHashtagSweep(row);
	}

	private void triggerHashtagSweep(BrandRow row) {
		hashtagSweep.execute(() -> runHashtagSweepSafely(row));
	}

	/**
	 * 해시태그 스윕 1회 — 등록 백필 꼬리(전 페이지 보강 뒤, ready에 영향 0)·replay 재등록·태그
	 * PUT/POST 즉시 트리거 3곳이 공유한다. core(또는 시드)는 이미 성공했으므로 여기 실패는
	 * backfill_error를 남기지 않는다(warn 로그만) — 다음 일일 스윕이 백스톱한다.
	 */
	private void runHashtagSweepSafely(BrandRow row) {
		try {
			hashtagCollect.sweep(row);
		} catch (RuntimeException e) {
			log.warn("브랜드 해시태그 스윕 실패(격리) — {} 다음 야간 스윕이 백스톱: {}", row.username(), e.toString());
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

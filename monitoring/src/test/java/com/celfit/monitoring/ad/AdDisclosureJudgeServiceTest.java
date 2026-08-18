package com.celfit.monitoring.ad;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.ad.AdDisclosureExtractor.Category;
import com.celfit.monitoring.ad.AdDisclosureExtractor.Disclosure;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.store.BrandPostMetaRepository;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AdDisclosureJudgeServiceTest {

	private static PostInfo post(String shortCode, String caption, String contentType,
			Boolean isPaidPartnership) {
		return new PostInfo(shortCode, "poster1", null, null, "uid1", contentType, caption, null,
				1700000000L, 1L, 1L, 1L, null, null, null, null, null, null, isPaidPartnership,
				true, false, false);
	}

	/** contentType=FEED이지만 videoUrl을 가진 단일 동영상 게시물 — HikerClient는 REELS/FEED 2값만
	 * 매핑하므로 일반 피드의 단일 동영상도 이 셰이프로 온다(코디네이터 리뷰 반영). */
	private static PostInfo feedVideoPost(String shortCode, String caption) {
		return new PostInfo(shortCode, "poster1", null, null, "uid1", "FEED", caption, null,
				1700000000L, 1L, 1L, 1L, null, null, null, null, "https://video.example/x.mp4", null, null,
				true, false, false);
	}

	@Test
	void 유료협찬_라벨이면_LLM_호출_없이_DISCLOSED() {
		FakeExtractor extractor = new FakeExtractor();
		FakeRepo repo = new FakeRepo();
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, extractor, Runnable::run);

		service.judgePosts(List.of(post("AAA", "무설명 캡션", "FEED", true)));

		assertThat(extractor.calls).isEmpty();
		assertThat(repo.written.get("AAA").verdict()).isEqualTo("DISCLOSED");
		assertThat(repo.written.get("AAA").source()).isEqualTo("RULE");
	}

	@Test
	void 캡션_공백_사진은_NOT_DISCLOSED_릴스는_UNCERTAIN() {
		FakeExtractor extractor = new FakeExtractor();
		FakeRepo repo = new FakeRepo();
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, extractor, Runnable::run);

		service.judgePosts(List.of(post("F1", "", "FEED", null), post("R1", "", "REELS", null)));

		assertThat(repo.written.get("F1").verdict()).isEqualTo("NOT_DISCLOSED");
		assertThat(repo.written.get("R1").verdict()).isEqualTo("UNCERTAIN");
		assertThat(extractor.calls).isEmpty();
	}

	@Test
	void FEED_동영상_캡션_공백은_UNCERTAIN() {
		FakeExtractor extractor = new FakeExtractor();
		FakeRepo repo = new FakeRepo();
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, extractor, Runnable::run);

		service.judgePosts(List.of(feedVideoPost("V1", "")));

		assertThat(repo.written.get("V1").verdict()).isEqualTo("UNCERTAIN");
		assertThat(extractor.calls).isEmpty();
	}

	@Test
	void FEED_동영상_문구_전무는_combine_경유_UNCERTAIN() {
		FakeExtractor extractor = new FakeExtractor();
		extractor.next = List.of();
		FakeRepo repo = new FakeRepo();
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, extractor, Runnable::run);

		service.judgePosts(List.of(feedVideoPost("V2", "그냥 오늘의 일상입니다")));

		assertThat(extractor.calls).containsExactly("그냥 오늘의 일상입니다");
		assertThat(repo.written.get("V2").verdict()).isEqualTo("UNCERTAIN");
	}

	@Test
	void Tier1_매칭_적절_위치면_LLM_생략하고_DISCLOSED() {
		FakeExtractor extractor = new FakeExtractor();
		FakeRepo repo = new FakeRepo();
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, extractor, Runnable::run);

		service.judgePosts(List.of(post("AAA", "오늘 소개 #광고", "FEED", null)));

		assertThat(extractor.calls).isEmpty();
		assertThat(repo.written.get("AAA").verdict()).isEqualTo("DISCLOSED");
		assertThat(repo.written.get("AAA").source()).isEqualTo("RULE");
	}

	@Test
	void Tier1_미매칭이면_LLM_호출_후_조합() {
		FakeExtractor extractor = new FakeExtractor();
		extractor.next = List.of(new Disclosure("체험단", Category.AMBIGUOUS));
		FakeRepo repo = new FakeRepo();
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, extractor, Runnable::run);

		service.judgePosts(List.of(post("AAA", "체험단 후기입니다", "FEED", null)));

		assertThat(extractor.calls).containsExactly("체험단 후기입니다");
		assertThat(repo.written.get("AAA").verdict()).isEqualTo("INSUFFICIENT");
	}

	@Test
	void 이미_같은_캡션으로_판정된_게시물은_재판정하지_않는다() {
		FakeExtractor extractor = new FakeExtractor();
		FakeRepo repo = new FakeRepo();
		String hash = md5("변경없는 캡션");
		repo.state.put("AAA", new BrandPostMetaRepository.AdJudgmentState("UNCERTAIN", hash));
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, extractor, Runnable::run);

		service.judgePosts(List.of(post("AAA", "변경없는 캡션", "FEED", null)));

		assertThat(extractor.calls).isEmpty();
		assertThat(repo.written).doesNotContainKey("AAA");
	}

	@Test
	void 캡션이_바뀌면_재판정한다() {
		FakeExtractor extractor = new FakeExtractor();
		FakeRepo repo = new FakeRepo();
		repo.state.put("AAA", new BrandPostMetaRepository.AdJudgmentState("NOT_DISCLOSED", md5("옛 캡션")));
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, extractor, Runnable::run);

		service.judgePosts(List.of(post("AAA", "새 캡션 #광고", "FEED", null)));

		assertThat(repo.written.get("AAA").verdict()).isEqualTo("DISCLOSED");
	}

	@Test
	void LLM_실패는_격리되고_verdict를_쓰지_않는다() {
		FakeExtractor extractor = new FakeExtractor();
		extractor.fail = true;
		FakeRepo repo = new FakeRepo();
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, extractor, Runnable::run);

		service.judgePosts(List.of(post("AAA", "체험단 후기", "FEED", null)));

		assertThat(repo.written).doesNotContainKey("AAA");
	}

	@Test
	void 빈_목록은_아무_일도_하지_않는다() {
		FakeExtractor extractor = new FakeExtractor();
		FakeRepo repo = new FakeRepo();
		new AdDisclosureJudgeService(repo, extractor, Runnable::run).judgePosts(List.of());
		assertThat(repo.written).isEmpty();
	}

	/**
	 * Task 8 리뷰 후속 — 기존 테스트는 전부 {@code Runnable::run}(동기)만 주입해 병렬 경로가 한 번도
	 * 실제 스레드로 실행되지 않았다. 소형 실제 스레드풀(2스레드)로 게시물 8건(그중 1건은 추출기가
	 * 예외를 던짐)을 판정해, 실패 1건만 verdict 미기록으로 격리되고 나머지 7건은 정상 기록되는지
	 * 확인한다. 관측한 스레드 이름이 2개 이상이어야 실제로 병렬 실행됐다고 볼 수 있다.
	 */
	@Test
	void 실제_스레드풀에서_병렬_판정하고_실패1건만_격리된다() throws InterruptedException {
		AtomicInteger seq = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
			Thread t = new Thread(r, "ad-disclosure-test-" + seq.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
		try {
			ThreadTrackingExtractor extractor = new ThreadTrackingExtractor("FAIL_TRIGGER");
			FakeRepo repo = new FakeRepo();
			AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, extractor, pool);

			List<PostInfo> posts = new ArrayList<>();
			for (int i = 1; i <= 8; i++) {
				String caption = (i == 5) ? "체험단 후기 FAIL_TRIGGER" : "체험단 후기 " + i;
				posts.add(post("P" + i, caption, "FEED", null));
			}

			service.judgePosts(posts);

			assertThat(repo.written).hasSize(7);
			assertThat(repo.written).doesNotContainKey("P5");
			for (int i = 1; i <= 8; i++) {
				if (i != 5) {
					assertThat(repo.written.get("P" + i).verdict()).isEqualTo("INSUFFICIENT");
				}
			}
			assertThat(extractor.calls).hasSize(8);
			assertThat(extractor.threadNames).as("실제 스레드풀이 여러 스레드로 작업을 나눠 실행했어야 한다")
					.hasSizeGreaterThan(1);
		} finally {
			pool.shutdown();
			assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	/**
	 * 미판정 잔여 백필(스펙 §7 개정) — 저장된 메타(brand_post_meta)만으로 Hiker 없이 판정한다.
	 * judgeCore는 PostInfo 경로와 공유하므로 여기서는 진입점 배선(findUnjudged→judgeOne(meta)→
	 * updateAdVerdict)과 Tier0 분기 재검증에 집중한다.
	 */
	@Test
	void 백필_저장된_메타_유료협찬_라벨이면_LLM_호출_없이_DISCLOSED() {
		FakeExtractor extractor = new FakeExtractor();
		FakeRepo repo = new FakeRepo();
		repo.unjudged = List.of(new BrandPostMetaRepository.UnjudgedPost("AAA", "무설명 캡션", "FEED", null, true));
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, extractor, Runnable::run);

		service.backfillUnjudged();

		assertThat(extractor.calls).isEmpty();
		assertThat(repo.written.get("AAA").verdict()).isEqualTo("DISCLOSED");
		assertThat(repo.written.get("AAA").source()).isEqualTo("RULE");
	}

	@Test
	void 백필_저장된_메타_캡션_공백_사진은_NOT_DISCLOSED_릴스는_UNCERTAIN() {
		FakeExtractor extractor = new FakeExtractor();
		FakeRepo repo = new FakeRepo();
		repo.unjudged = List.of(
				new BrandPostMetaRepository.UnjudgedPost("F1", "", "FEED", null, null),
				new BrandPostMetaRepository.UnjudgedPost("R1", "", "REELS", null, null));
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, extractor, Runnable::run);

		service.backfillUnjudged();

		assertThat(repo.written.get("F1").verdict()).isEqualTo("NOT_DISCLOSED");
		assertThat(repo.written.get("R1").verdict()).isEqualTo("UNCERTAIN");
		assertThat(extractor.calls).isEmpty();
	}

	@Test
	void 백필_저장된_메타_FEED_동영상_캡션_공백은_UNCERTAIN() {
		FakeRepo repo = new FakeRepo();
		repo.unjudged = List.of(new BrandPostMetaRepository.UnjudgedPost("V1", "", "FEED",
				"https://video.example/x.mp4", null));
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, new FakeExtractor(), Runnable::run);

		service.backfillUnjudged();

		assertThat(repo.written.get("V1").verdict()).isEqualTo("UNCERTAIN");
	}

	@Test
	void 백필_미판정_잔여가_없으면_findUnjudged를_생략한다() {
		FakeRepo repo = new FakeRepo();   // unjudged 기본값 빈 목록 → countUnjudged() 0
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, new FakeExtractor(), Runnable::run);

		AdDisclosureJudgeService.BackfillOutcome outcome = service.backfillUnjudged();

		assertThat(outcome.remaining()).isZero();
		assertThat(outcome.processed()).isZero();
		assertThat(repo.countUnjudgedCalls).isEqualTo(1);
		assertThat(repo.findUnjudgedCalls).isZero();
	}

	/**
	 * 상한 제거(2026-08-18) 계약 — limit 파라미터가 없다. 잔량이 배치 크기(테스트에서는 2로
	 * 주입)보다 많아도 findUnjudged를 반복 호출해 전량 처리한다. FakeRepo.findUnjudged는 이미
	 * written된 short_code를 제외하므로, 매 배치가 성공하면 다음 배치가 자연히 새 항목을 만난다
	 * (실 DB의 {@code ad_verdict IS NULL} 재조회 동형).
	 */
	@Test
	void 백필_상한_없이_배치를_반복해_전량_처리한다() {
		FakeRepo repo = new FakeRepo();
		repo.unjudged = List.of(
				new BrandPostMetaRepository.UnjudgedPost("F1", "", "FEED", null, null),
				new BrandPostMetaRepository.UnjudgedPost("F2", "", "FEED", null, null),
				new BrandPostMetaRepository.UnjudgedPost("F3", "", "FEED", null, null),
				new BrandPostMetaRepository.UnjudgedPost("F4", "", "FEED", null, null),
				new BrandPostMetaRepository.UnjudgedPost("F5", "", "FEED", null, null));
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, new FakeExtractor(), Runnable::run, 2);

		AdDisclosureJudgeService.BackfillOutcome outcome = service.backfillUnjudged();

		assertThat(outcome.remaining()).isEqualTo(5);    // 시작 시점 전체 잔량
		assertThat(outcome.processed()).isEqualTo(5);    // 상한 없이 전량 처리
		assertThat(repo.findUnjudgedCalls).isEqualTo(3); // 2+2+1건씩 3배치
		assertThat(repo.written).hasSize(5);
	}

	/**
	 * 영구 실패 방어(2026-08-18) — 한 배치가 통째로 계속 실패해도(verdict NULL 유지) 같은
	 * short_code를 이번 호출 안에서 무한히 재조회하지 않는다. attempted 집합에 걸려 fresh가
	 * 비면 루프를 종료한다.
	 */
	@Test
	void 백필_영구_실패_배치는_이번_호출에서_무한_재조회되지_않는다() {
		FakeExtractor extractor = new FakeExtractor();
		extractor.fail = true;
		FakeRepo repo = new FakeRepo();
		repo.unjudged = List.of(new BrandPostMetaRepository.UnjudgedPost("AAA", "체험단 후기", "FEED", null, null));
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, extractor, Runnable::run, 500);

		AdDisclosureJudgeService.BackfillOutcome outcome = service.backfillUnjudged();

		assertThat(repo.written).doesNotContainKey("AAA");
		assertThat(outcome.processed()).isEqualTo(1);   // 시도는 했다(성공은 아님)
		assertThat(repo.findUnjudgedCalls).isEqualTo(2); // 1회차(시도) + 2회차(전부 재조회, fresh 없음 → 종료)
	}

	@Test
	void 백필_LLM_실패_1건만_격리되고_나머지는_기록된다() {
		FakeExtractor extractor = new FakeExtractor();
		extractor.fail = true;
		FakeRepo repo = new FakeRepo();
		repo.unjudged = List.of(
				new BrandPostMetaRepository.UnjudgedPost("F1", "", "FEED", null, null),   // 공백 캡션 — LLM 미호출
				new BrandPostMetaRepository.UnjudgedPost("AAA", "체험단 후기", "FEED", null, null));   // LLM 호출 → 실패
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, extractor, Runnable::run);

		service.backfillUnjudged();

		assertThat(repo.written.get("F1").verdict()).isEqualTo("NOT_DISCLOSED");
		assertThat(repo.written).doesNotContainKey("AAA");
	}

	/**
	 * 동시 실행 방어(2026-08-18) — 기동 백필과 스윕 말미 백필이 겹칠 수 있어 AtomicBoolean 가드를
	 * 둔다. 첫 호출이 countUnjudged() 안에서 대기하는 동안 두 번째 호출은 즉시 (0,0)을 반환하고
	 * findUnjudged를 전혀 호출하지 않아야 한다.
	 */
	@Test
	void 백필_이미_실행_중이면_두번째_호출은_즉시_스킵된다() throws Exception {
		CountDownLatch firstEnteredCount = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		FakeRepo repo = new FakeRepo() {
			@Override
			public int countUnjudged() {
				firstEnteredCount.countDown();
				try {
					releaseFirst.await(5, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return super.countUnjudged();
			}
		};
		repo.unjudged = List.of(new BrandPostMetaRepository.UnjudgedPost("AAA", "", "FEED", null, null));
		AdDisclosureJudgeService service = new AdDisclosureJudgeService(repo, new FakeExtractor(), Runnable::run);

		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<AdDisclosureJudgeService.BackfillOutcome> firstCall = pool.submit(service::backfillUnjudged);
			assertThat(firstEnteredCount.await(5, TimeUnit.SECONDS)).as("첫 호출이 countUnjudged에 진입해야 한다").isTrue();

			AdDisclosureJudgeService.BackfillOutcome second = service.backfillUnjudged();
			assertThat(second.processed()).isZero();
			assertThat(second.remaining()).isZero();
			assertThat(repo.findUnjudgedCalls).isZero();   // 가드에 걸려 findUnjudged 자체가 안 나간다

			releaseFirst.countDown();
			AdDisclosureJudgeService.BackfillOutcome first = firstCall.get(5, TimeUnit.SECONDS);
			assertThat(first.processed()).isEqualTo(1);
			assertThat(repo.written).containsKey("AAA");
		} finally {
			pool.shutdown();
			assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private static String md5(String s) {
		try {
			var digest = MessageDigest.getInstance("MD5").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : digest) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static final class FakeExtractor implements AdDisclosureExtractor {
		final List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
		List<Disclosure> next = List.of();
		boolean fail;

		@Override
		public List<Disclosure> extract(String caption) {
			calls.add(caption);
			if (fail) {
				throw new IllegalStateException("LLM 호출 실패(테스트)");
			}
			return next;
		}
	}

	/** 실제 스레드풀 테스트 전용 — caption에 {@code failMarker}가 포함되면 예외를 던지고, 그 외엔
	 * 항상 같은 Disclosure를 돌려준다. 호출이 실행된 스레드 이름을 모아 실제 병렬 실행을 검증한다. */
	private static final class ThreadTrackingExtractor implements AdDisclosureExtractor {
		final List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
		final Set<String> threadNames = new CopyOnWriteArraySet<>();
		private final String failMarker;

		ThreadTrackingExtractor(String failMarker) {
			this.failMarker = failMarker;
		}

		@Override
		public List<Disclosure> extract(String caption) {
			calls.add(caption);
			threadNames.add(Thread.currentThread().getName());
			try {
				Thread.sleep(10); // 여러 태스크가 겹치도록 — 풀의 스레드 2개가 모두 쓰이게 유도
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
			if (caption.contains(failMarker)) {
				throw new IllegalStateException("LLM 호출 실패(테스트)");
			}
			return List.of(new Disclosure("체험단", Category.AMBIGUOUS));
		}
	}

	/** 동시 실행 방어 테스트가 익명 서브클래스로 countUnjudged()를 가로채야 해서 final이 아니다. */
	private static class FakeRepo extends BrandPostMetaRepository {
		final Map<String, BrandPostMetaRepository.AdJudgmentState> state = new HashMap<>();
		final Map<String, AdVerdictResult> written = new ConcurrentHashMap<>();
		List<BrandPostMetaRepository.UnjudgedPost> unjudged = List.of();
		int findUnjudgedCalls;
		int countUnjudgedCalls;

		FakeRepo() {
			super(null);
		}

		@Override
		public Map<String, BrandPostMetaRepository.AdJudgmentState> findAdJudgmentState(
				java.util.Collection<String> shortCodes) {
			Map<String, BrandPostMetaRepository.AdJudgmentState> out = new HashMap<>();
			for (String c : shortCodes) {
				if (state.containsKey(c)) {
					out.put(c, state.get(c));
				}
			}
			return out;
		}

		@Override
		public void updateAdVerdict(String shortCode, AdVerdictResult result, String captionHash,
				java.time.Instant judgedAt) {
			written.put(shortCode, result);
		}

		/** 실 DB 동형화 — {@code written}(판정 기록됨)에 이미 있는 short_code는 더 이상 미판정이
		 * 아니므로 후보에서 빠진다. 이걸 반영해야 다중 배치 루프가 실 {@code ad_verdict IS NULL}
		 * 재조회처럼 자연히 수렴한다(성공한 항목이 다음 조회에서 빠지고, 그만큼 뒤에 있던 새
		 * 항목이 LIMIT 윈도우에 들어온다). */
		@Override
		public List<BrandPostMetaRepository.UnjudgedPost> findUnjudged(int limit) {
			findUnjudgedCalls++;
			List<BrandPostMetaRepository.UnjudgedPost> remaining =
					unjudged.stream().filter(m -> !written.containsKey(m.shortCode())).toList();
			return remaining.size() > limit ? remaining.subList(0, limit) : remaining;
		}

		@Override
		public int countUnjudged() {
			countUnjudgedCalls++;
			return (int) unjudged.stream().filter(m -> !written.containsKey(m.shortCode())).count();
		}
	}
}

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

	private static final class FakeRepo extends BrandPostMetaRepository {
		final Map<String, BrandPostMetaRepository.AdJudgmentState> state = new HashMap<>();
		final Map<String, AdVerdictResult> written = new ConcurrentHashMap<>();

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
	}
}

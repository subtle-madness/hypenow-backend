package com.celfit.monitoring.ad;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.ad.AdDisclosureExtractor.Category;
import com.celfit.monitoring.ad.AdDisclosureExtractor.Disclosure;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.store.BrandPostMetaRepository;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class AdDisclosureJudgeServiceTest {

	private static PostInfo post(String shortCode, String caption, String contentType,
			Boolean isPaidPartnership) {
		return new PostInfo(shortCode, "poster1", null, null, "uid1", contentType, caption, null,
				1700000000L, 1L, 1L, 1L, null, null, null, null, null, null, isPaidPartnership,
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

package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.hiker.CommentInfo;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.hiker.PostInfo;
import com.celfit.monitoring.hiker.ProfileInfo;
import com.celfit.monitoring.store.CommentRepository;
import com.celfit.monitoring.store.SnapshotRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 댓글 수집 페이지 수 분리(07-31, comment-pages 1→3 상향 + registration-comment-pages 신설) —
 * 스윕용 {@link CollectService#collectComments}는 commentPages(운영 3페이지)를, 등록용
 * {@link CollectService#collectCommentsForRegistration}은 registrationCommentPages(항상 1페이지)를
 * 쓴다. 응답이 계속 더 있다고(has_more_comments=true) 말해도 각자 설정된 페이지 수에서 멈춰야
 * 분리가 실제로 동작하는 것이다. CommentRepository는 upsert를 no-op으로 갈아 끼운 대역이라
 * Testcontainers 없이 순수 fetch 콜 횟수만 검증한다.
 */
class CollectServiceTest {

	/** upsertForPost를 no-op으로 바꾼 대역 — 이 테스트는 fetch 콜 횟수만 보므로 DB가 필요 없다. */
	private static final class NoopCommentRepository extends CommentRepository {
		NoopCommentRepository() {
			super(null);
		}

		@Override
		public void upsertForPost(String shortCode, List<CommentInfo> comments) {
			// no-op
		}
	}

	/** 매 페이지 유효 댓글 1건(pk를 콜 순번으로 바꿔 무진전 가드를 피한다) + has_more_comments:true. */
	private static String alwaysMorePage(int callIndex) {
		return """
				{"response":{"comments":[
				{"pk":"c%d","text":"댓글%d","comment_like_count":1,
				 "created_at_utc":1700000000,"user":{"username":"fan"},"preview_child_comments":[]}
				],"has_more_comments":true},"next_page_id":"cursor-%d"}""".formatted(callIndex, callIndex, callIndex);
	}

	@Test
	void 스윕용_collectComments는_commentPages를_쓰고_등록용은_registrationCommentPages를_쓴다() {
		List<String> calls = new ArrayList<>();
		var client = new HikerClient(path -> {
			calls.add(path);
			return alwaysMorePage(calls.size());
		});
		// enumeratePages=1(이 테스트와 무관), commentPages=3(스윕), registrationCommentPages=1(등록)
		var collect = new CollectService(client, null, new NoopCommentRepository(), new FbRepo(Set.of()), 1, 3, 1);

		collect.collectComments("DbV7LgZsKG8", "rarebeauty");
		assertThat(calls).hasSize(3);   // 응답이 계속 더 있다고 해도 설정된 3페이지에서 멈춘다

		calls.clear();
		collect.collectCommentsForRegistration("DbV7LgZsKG8", "rarebeauty");
		assertThat(calls).hasSize(1);   // 등록은 응답이 더 있어도 1페이지에서 멈춘다
	}

	// ── FB 몫 최초 1회 재시도(08-03, findings §2 결론 4) ─────────────────────
	// Hiker 세션의 20~30%만 FB 교차게시 몫(fb_play_count)을 준다. fb를 한 번도 관측 못 한 릴스가
	// fb 없는 응답을 받으면 딱 1회만 재조회한다 — 관측 이력이 생긴 뒤에는 캐리포워드가 있어 재시도가
	// 필요 없고, 매번 재시도하면 콜 비용이 기대 3~5배가 된다.

	/** fb 관측 이력 조회를 고정 응답으로 갈아 끼운 대역 — DB 없이 재시도 분기만 검증한다. */
	private static final class FbRepo extends SnapshotRepository {
		private final Set<String> observed;

		FbRepo(Set<String> observed) {
			super(null);
			this.observed = observed;
		}

		@Override
		public Set<String> codesWithFbObserved(Collection<String> codes) {
			return observed;
		}
	}

	/** 저장 인자를 기록만 하는 대역 — 이 테스트는 저장 내용(fb 머지 여부)과 콜 수만 본다. */
	private static final class RecordingWriter extends SnapshotWriter {
		final List<PostInfo> savedPosts = new ArrayList<>();

		RecordingWriter() {
			super(null, null, null, null);
		}

		@Override
		public void saveAccount(String username, LocalDate on, ProfileInfo profile, List<PostInfo> posts) {
			savedPosts.addAll(posts);
		}

		@Override
		public void savePost(LocalDate on, PostInfo post) {
			savedPosts.add(post);
		}
	}

	private static final String PROFILE = """
			{"user":{"pk":999,"is_private":false,"follower_count":10,"following_count":1,
			"media_count":1,"full_name":"n","profile_pic_url":"u"},"status":"ok"}""";

	private static final String MEDIAS_ONE_REEL = """
			{"response":{"items":[{"code":"ReelA","taken_at":1700000000,"product_type":"clips",
			"like_count":1,"comment_count":1,"media_repost_count":1}],
			"more_available":false},"next_page_id":null}""";

	private static String clips(boolean withFb) {
		return withFb
				? """
				{"response":{"items":[{"media":{"code":"ReelA","product_type":"clips",
				"play_count":305,"ig_play_count":222,"fb_play_count":83}}],
				"paging_info":{"more_available":false}},"next_page_id":null}"""
				: """
				{"response":{"items":[{"media":{"code":"ReelA","product_type":"clips",
				"play_count":222,"ig_play_count":222}}],
				"paging_info":{"more_available":false}},"next_page_id":null}""";
	}

	@Test
	void 열거_fb_미관측_신규_릴스는_clips를_1회_재조회해_fb를_머지한다() {
		List<String> calls = new ArrayList<>();
		var client = new HikerClient(path -> {
			calls.add(path);
			if (path.startsWith("/v2/user/by/username")) return PROFILE;
			if (path.startsWith("/v2/user/clips")) {
				long clipCalls = calls.stream().filter(p -> p.startsWith("/v2/user/clips")).count();
				return clips(clipCalls >= 2);   // 1차는 IG 전용 세션, 재시도에서 합산 세션
			}
			return MEDIAS_ONE_REEL;
		});
		var writer = new RecordingWriter();
		var collect = new CollectService(client, writer, new NoopCommentRepository(), new FbRepo(Set.of()), 1, 1, 1);

		collect.collectAccount("acct");

		assertThat(calls.stream().filter(p -> p.startsWith("/v2/user/clips"))).hasSize(2);
		assertThat(writer.savedPosts).hasSize(1);
		assertThat(writer.savedPosts.getFirst().views()).isEqualTo(222L);   // IG 몫은 원 콜 값 유지
		assertThat(writer.savedPosts.getFirst().fbPlays()).isEqualTo(83L);  // FB 몫만 재시도에서 머지
	}

	@Test
	void 열거_fb_관측_이력이_있으면_재시도하지_않는다() {
		List<String> calls = new ArrayList<>();
		var client = new HikerClient(path -> {
			calls.add(path);
			if (path.startsWith("/v2/user/by/username")) return PROFILE;
			if (path.startsWith("/v2/user/clips")) return clips(false);
			return MEDIAS_ONE_REEL;
		});
		var collect = new CollectService(client, new RecordingWriter(), new NoopCommentRepository(),
				new FbRepo(Set.of("ReelA")), 1, 1, 1);

		collect.collectAccount("acct");

		assertThat(calls.stream().filter(p -> p.startsWith("/v2/user/clips"))).hasSize(1);
	}

	@Test
	void 열거_이번_콜에_fb가_실렸으면_재시도하지_않는다() {
		List<String> calls = new ArrayList<>();
		var client = new HikerClient(path -> {
			calls.add(path);
			if (path.startsWith("/v2/user/by/username")) return PROFILE;
			if (path.startsWith("/v2/user/clips")) return clips(true);
			return MEDIAS_ONE_REEL;
		});
		var collect = new CollectService(client, new RecordingWriter(), new NoopCommentRepository(),
				new FbRepo(Set.of()), 1, 1, 1);

		collect.collectAccount("acct");

		assertThat(calls.stream().filter(p -> p.startsWith("/v2/user/clips"))).hasSize(1);
	}

	private static String singlePost(boolean withFb) {
		return withFb
				? """
				{"num_results":1,"items":[{"code":"Xx1","product_type":"clips","like_count":1,
				"play_count":305,"ig_play_count":222,"fb_play_count":83,"user":{"username":"acct"}}]}"""
				: """
				{"num_results":1,"items":[{"code":"Xx1","product_type":"clips","like_count":1,
				"play_count":222,"ig_play_count":222,"user":{"username":"acct"}}]}""";
	}

	@Test
	void 단건_fb_미관측이면_1회_재조회하고_재시도_응답을_쓴다() {
		List<String> calls = new ArrayList<>();
		var client = new HikerClient(path -> {
			calls.add(path);
			return singlePost(calls.size() >= 2);
		});
		var writer = new RecordingWriter();
		var collect = new CollectService(client, writer, new NoopCommentRepository(), new FbRepo(Set.of()), 1, 1, 1);

		collect.collectPost("Xx1");

		assertThat(calls).hasSize(2);
		assertThat(writer.savedPosts.getFirst().fbPlays()).isEqualTo(83L);
	}

	@Test
	void 단건_재시도도_fb가_없으면_원_결과로_저장하고_더_부르지_않는다() {
		List<String> calls = new ArrayList<>();
		var client = new HikerClient(path -> {
			calls.add(path);
			return singlePost(false);
		});
		var writer = new RecordingWriter();
		var collect = new CollectService(client, writer, new NoopCommentRepository(), new FbRepo(Set.of()), 1, 1, 1);

		collect.collectPost("Xx1");

		assertThat(calls).hasSize(2);   // 재시도는 딱 1회 — "1회만"이 안 지켜지면 여기가 3 이상이 된다
		assertThat(writer.savedPosts.getFirst().fbPlays()).isNull();
		assertThat(writer.savedPosts.getFirst().views()).isEqualTo(222L);
	}

	@Test
	void 단건_fb_관측_이력이_있으면_재조회하지_않는다() {
		List<String> calls = new ArrayList<>();
		var client = new HikerClient(path -> {
			calls.add(path);
			return singlePost(false);
		});
		var writer = new RecordingWriter();
		var collect = new CollectService(client, writer, new NoopCommentRepository(),
				new FbRepo(Set.of("Xx1")), 1, 1, 1);

		collect.collectPost("Xx1");

		assertThat(calls).hasSize(1);
	}
}

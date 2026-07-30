package com.celfit.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.monitoring.hiker.CommentInfo;
import com.celfit.monitoring.hiker.HikerClient;
import com.celfit.monitoring.store.CommentRepository;
import java.util.ArrayList;
import java.util.List;
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
		var collect = new CollectService(client, null, new NoopCommentRepository(), 1, 3, 1);

		collect.collectComments("DbV7LgZsKG8", "rarebeauty");
		assertThat(calls).hasSize(3);   // 응답이 계속 더 있다고 해도 설정된 3페이지에서 멈춘다

		calls.clear();
		collect.collectCommentsForRegistration("DbV7LgZsKG8", "rarebeauty");
		assertThat(calls).hasSize(1);   // 등록은 응답이 더 있어도 1페이지에서 멈춘다
	}
}

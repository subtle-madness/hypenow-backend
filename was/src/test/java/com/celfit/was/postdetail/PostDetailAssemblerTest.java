package com.celfit.was.postdetail;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.contract.analysis.Account;
import com.celfit.contract.analysis.Content;
import com.celfit.contract.analysis.ContentComment;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostDetailAssemblerTest {

	// 게시일(2026-06-28T00:00Z)로부터 10일 경과 시점으로 고정
	private final Clock fixedClock =
			Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

	private final PostDetailAssembler assembler = new PostDetailAssembler(fixedClock);

	private Content reels() {
		return new Content("mari01", "marimood", "https://thumb/mari01.jpg", "쿨톤 여름 침착 조합",
				OffsetDateTime.parse("2026-06-28T00:00:00Z"), "reels", new BigDecimal("18.0"),
				"https://www.instagram.com/p/mari01/", 1911943L, 32969L, 488L, 1911943L);
	}

	private Account account() {
		return new Account("marimood", "마리 MARI", "https://pic/mari.jpg", 16586L);
	}

	@Test
	void 행을_모달_블록_구조로_조립한다() {
		List<ContentComment> comments = List.of(
				new ContentComment(1L, "mari01", "hye***", "이거 어디서 살 수 있어요??", 342L),
				new ContentComment(3L, "mari01", "seo***", "언니 피부 미쳤다", 289L));

		PostDetailResponse response = assembler.toResponse(reels(), account(), comments);

		assertThat(response.post().shortCode()).isEqualTo("mari01");
		assertThat(response.post().daysSincePosted()).isEqualTo(10L);
		// (32969+488)/1911943 = 0.0175 (4자리 HALF_UP)
		assertThat(response.post().engagementRate()).isEqualByComparingTo(new BigDecimal("0.0175"));
		assertThat(response.post().hypeScore()).isEqualTo(1911943L);
		assertThat(response.account().displayName()).isEqualTo("마리 MARI");
		assertThat(response.comments().collectedCount()).isEqualTo(2);
		assertThat(response.comments().items().getFirst().authorMasked()).isEqualTo("hye***");
		assertThat(response.comments().items().getFirst().likeCount()).isEqualTo(342L);
	}

	@Test
	void 피드는_조회수가_없어_참여율이_null이다() {
		Content feed = new Content("mari02", "marimood", null, "피드 게시물",
				OffsetDateTime.parse("2026-07-01T00:00:00Z"), "feed", null,
				"https://www.instagram.com/p/mari02/", null, 2000L, 100L, 2100L);

		PostDetailResponse response = assembler.toResponse(feed, account(), List.of());

		assertThat(response.post().engagementRate()).isNull();
		assertThat(response.post().daysSincePosted()).isEqualTo(7L);
		assertThat(response.comments().collectedCount()).isZero();
		assertThat(response.comments().items()).isEmpty();
	}

	@Test
	void 계정이_없으면_account_블록이_null이다() {
		PostDetailResponse response = assembler.toResponse(reels(), null, List.of());

		assertThat(response.account()).isNull();
		assertThat(response.post().shortCode()).isEqualTo("mari01");
	}

	@Test
	void 게시일이_null이면_경과일도_null이다() {
		Content undated = new Content("mari03", "marimood", null, null,
				null, "reels", null, null, 1000L, 10L, 1L, 1000L);

		PostDetailResponse response = assembler.toResponse(undated, account(), List.of());

		assertThat(response.post().daysSincePosted()).isNull();
		assertThat(response.post().engagementRate()).isEqualByComparingTo(new BigDecimal("0.0110"));
	}
}

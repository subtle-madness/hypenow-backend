package com.celfit.monitoring.hiker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HikerClientTest {

	private static String fixture(String name) {
		try (var in = HikerClientTest.class.getResourceAsStream("/hiker/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** 경로별로 픽스처를 돌려주는 fake — 열거는 medias·clips 두 콜을 쏜다. */
	private static HikerHttp fakeHttp() {
		return path -> {
			if (path.startsWith("/v2/user/by/username")) return fixture("profile.json");
			if (path.startsWith("/v2/user/medias")) return fixture("medias.json");
			if (path.startsWith("/v2/user/clips")) return fixture("clips.json");
			return fixture("media-by-code.json");
		};
	}

	@Test
	void 프로필_파싱() {
		HikerClient client = new HikerClient(fakeHttp());
		ProfileInfo p = client.fetchProfile("rarebeauty");
		assertThat(p.userId()).isNotBlank();
		assertThat(p.followers()).isPositive();
		assertThat(p.rawJson()).isNotBlank();
	}

	@Test
	void 열거_파싱_릴스는_조회수_머지_피드는_저장공유_null() {
		HikerClient client = new HikerClient(fakeHttp());
		var posts = client.fetchRecentPosts("rarebeauty", "3109786630", 1);
		assertThat(posts).hasSize(12);                       // 1페이지 12건(findings §3)
		assertThat(posts).allSatisfy(p -> {
			assertThat(p.shortCode()).isNotBlank();
			assertThat(p.likes()).isNotNull();
			assertThat(p.comments()).isNotNull();
			assertThat(p.reposts()).isNotNull();             // media_repost_count는 전 타입 제공
		});
		// 핀 고정(2023년) 게시물이 맨 앞에 오지 않게 taken_at 내림차순 재정렬됨(findings §3)
		assertThat(posts.getFirst().takenAt()).isGreaterThanOrEqualTo(posts.getLast().takenAt());
		var reel = posts.stream().filter(p -> p.contentType().equals("REELS")).findFirst().orElseThrow();
		assertThat(reel.saves()).isNotNull();
		assertThat(reel.shares()).isNotNull();
		assertThat(reel.views()).isPositive();               // clips 열거에서 머지된 play_count
		var feed = posts.stream().filter(p -> p.contentType().equals("FEED")).findFirst().orElseThrow();
		assertThat(feed.views()).isNull();                   // 피드는 조회수 영구 null
		assertThat(feed.saves()).isNull();
		assertThat(feed.shares()).isNull();
	}

	@Test
	void 단건_파싱_릴스는_6지표_전량() {
		HikerClient client = new HikerClient(path -> fixture("media-by-code.json"));
		PostInfo p = client.fetchPost("DbV7LgZsKG8");
		assertThat(p.contentType()).isEqualTo("REELS");
		assertThat(p.caption()).isNotNull();
		assertThat(p.likes()).isPositive();
		assertThat(p.comments()).isPositive();
		assertThat(p.views()).isPositive();
		assertThat(p.saves()).isPositive();
		assertThat(p.shares()).isPositive();
		assertThat(p.reposts()).isPositive();
	}

	@Test
	void 단건_파싱_피드는_조회_저장_공유가_null() {
		HikerClient client = new HikerClient(path -> fixture("media-by-code-feed.json"));
		PostInfo p = client.fetchPost("DbOMP1_CY18");
		assertThat(p.contentType()).isEqualTo("FEED");
		assertThat(p.likes()).isPositive();
		assertThat(p.views()).isNull();
		assertThat(p.saves()).isNull();
		assertThat(p.shares()).isNull();
	}

	@Test
	void _404는_SubjectNotFound로() {
		HikerClient client = new HikerClient(path -> { throw new SubjectNotFoundException("404"); });
		assertThatThrownBy(() -> client.fetchProfile("ghost"))
				.isInstanceOf(SubjectNotFoundException.class);
	}

	@Test
	void 비공개_계정은_PrivateAccount로() {
		HikerClient client = new HikerClient(
				path -> "{\"user\":{\"pk\":1,\"is_private\":true},\"status\":\"ok\"}");
		assertThatThrownBy(() -> client.fetchProfile("secret"))
				.isInstanceOf(PrivateAccountException.class);
	}

	/** 2페이지째로 내려오는 정상 응답 — 새 숏코드 1건, more_available=false로 종료. */
	private static final String PAGE2 = """
			{"response":{"items":[{"code":"ZzPage2Only","taken_at":1700000000,"product_type":"feed",
			"like_count":7,"comment_count":3,"media_repost_count":1,"caption":{"text":"2페이지"}}],
			"more_available":false,"num_results":1},"next_page_id":null}""";

	@Test
	void 열거_2페이지는_next_page_id를_커서로_전달하고_다음_페이지를_이어붙인다() {
		List<String> calls = new ArrayList<>();
		HikerClient client = new HikerClient(path -> {
			calls.add(path);
			if (path.startsWith("/v2/user/clips")) return fixture("clips.json");
			return path.contains("page_id=") ? PAGE2 : fixture("medias.json");
		});
		var posts = client.fetchRecentPosts("rarebeauty", "3109786630", 2);
		assertThat(posts).hasSize(13);                        // 1페이지 12 + 2페이지 1
		assertThat(posts).extracting(PostInfo::shortCode).contains("ZzPage2Only");
		var medias = calls.stream().filter(p -> p.startsWith("/v2/user/medias")).toList();
		assertThat(medias).hasSize(2);
		assertThat(medias.get(0)).doesNotContain("page_id");
		assertThat(medias.get(1)).contains("&page_id=3946974539133803409_3109786630");
		// 클립 커서는 base64라 URL 인코딩이 필요하다(`==` → `%3D%3D`)
		var clips = calls.stream().filter(p -> p.startsWith("/v2/user/clips")).toList();
		assertThat(clips).hasSize(2);
		assertThat(clips.get(1)).contains("&page_id=QVFD").endsWith("%3D%3D");
	}

	@Test
	void 커서가_전진하지_않으면_경고하고_중단한다() {
		// 커서 파라미터명이 틀려 API가 같은 1페이지를 계속 돌려주는 상황.
		// dedupe가 결과를 12건으로 보정해버려 조용히 넘어가면 콜만 배로 나간다 → 즉시 중단해야 한다.
		List<String> calls = new ArrayList<>();
		HikerClient client = new HikerClient(path -> {
			calls.add(path);
			if (path.startsWith("/v2/user/clips")) return fixture("clips.json");
			return fixture("medias.json");
		});
		assertThat(client.fetchRecentPosts("rarebeauty", "3109786630", 5)).hasSize(12);
		// 5페이지를 요청했어도 2페이지째에서 새 숏코드 0건을 감지하고 멈춘다
		assertThat(calls.stream().filter(p -> p.startsWith("/v2/user/medias"))).hasSize(2);
		assertThat(calls.stream().filter(p -> p.startsWith("/v2/user/clips"))).hasSize(2);
	}

	@Test
	void more_available가_false면_페이지가_남아도_멈춘다() {
		List<String> calls = new ArrayList<>();
		HikerClient client = new HikerClient(path -> {
			calls.add(path);
			if (path.startsWith("/v2/user/clips")) return fixture("clips.json");
			// next_page_id는 있지만 more_available=false — 커서만 믿고 더 부르면 안 된다
			return """
					{"response":{"items":[{"code":"Only1","taken_at":1700000000,"product_type":"feed",
					"like_count":1,"comment_count":1,"media_repost_count":1}],"more_available":false},
					"next_page_id":"cursor-should-not-be-used"}""";
		});
		assertThat(client.fetchRecentPosts("rarebeauty", "3109786630", 3)).hasSize(1);
		assertThat(calls.stream().filter(p -> p.startsWith("/v2/user/medias"))).hasSize(1);
	}

	@Test
	void 클립_보강이_실패해도_열거는_계속되고_조회수만_null() {
		HikerClient client = new HikerClient(path -> {
			if (path.startsWith("/v2/user/clips")) throw new HikerFetchException("클립 500");
			return fixture("medias.json");
		});
		var posts = client.fetchRecentPosts("rarebeauty", "3109786630", 1);
		assertThat(posts).hasSize(12);
		assertThat(posts).allSatisfy(p -> assertThat(p.views()).isNull());
	}

	/** 단건은 usernameHint가 없어 user.username이 유일한 소유 계정 출처다 — 없으면 셰이프 이상. */
	@Test
	void 단건_응답에_소유_계정이_없으면_HikerFetch로() {
		HikerClient client = new HikerClient(path -> """
				{"num_results":1,"items":[{"code":"Xx1","product_type":"clips","like_count":1}]}""");
		assertThatThrownBy(() -> client.fetchPost("Xx1"))
				.isInstanceOf(HikerFetchException.class);
	}

	@Test
	void 단건_응답이_비면_SubjectNotFound로() {
		HikerClient client = new HikerClient(path -> "{\"num_results\":0,\"items\":[]}");
		assertThatThrownBy(() -> client.fetchPost("gone"))
				.isInstanceOf(SubjectNotFoundException.class);
	}
}

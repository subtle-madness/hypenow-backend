package com.celfit.monitoring.hiker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
			return fixture("media-info-by-code.json");
		};
	}

	@Test
	void 프로필_파싱() {
		HikerClient client = new HikerClient(fakeHttp());
		ProfileInfo p = client.fetchProfile("rarebeauty");
		assertThat(p.userId()).isNotBlank();
		assertThat(p.followers()).isPositive();
		assertThat(p.rawJson()).isNotBlank();
		// profile_meta 저장용(계약 §3, v1.1) — full_name·profile_pic_url
		assertThat(p.fullName()).isEqualTo("Rare Beauty by Selena Gomez");
		assertThat(p.profilePicUrl()).isNotBlank();
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
			// post_meta 썸네일(계약 §3) — image_versions2.candidates[0].url(픽스처 실측: 전 게시물 존재)
			assertThat(p.thumbnailUrl()).isNotBlank();
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

	/** 저장·리포스트 키를 안 실은 세션의 medias 응답 — 같은 릴스가 clips 응답에는 키와 함께 실려 있다. */
	private static final String MEDIAS_NO_METRIC_KEYS = """
			{"response":{"items":[{"code":"ReelM","taken_at":1700000000,"product_type":"clips",
			"like_count":11,"comment_count":2,"caption":{"text":"m"}}],
			"more_available":false},"next_page_id":null}""";

	private static final String CLIPS_WITH_METRIC_KEYS = """
			{"response":{"items":[{"media":{"code":"ReelM","product_type":"clips",
			"ig_play_count":100,"save_count":10,"reshare_count":20,"media_repost_count":30}}],
			"paging_info":{"more_available":false}},"next_page_id":null}""";

	@Test
	void 열거_medias가_저장공유리포스트를_안_실어도_clips_관측을_머지한다() {
		// 세션 복권(08-04 실측): 저장·리포스트 키는 콜 단위로 전부 실리거나 전부 빠진다.
		// medias가 꽝 세션이어도 clips가 당첨 세션이면 그 관측을 버리면 안 된다(존재율 45% vs 30%).
		HikerClient client = new HikerClient(path ->
				path.startsWith("/v2/user/clips") ? CLIPS_WITH_METRIC_KEYS : MEDIAS_NO_METRIC_KEYS);
		var posts = client.fetchRecentPosts("acct", "999", 1);
		assertThat(posts).hasSize(1);
		assertThat(posts.getFirst().saves()).isEqualTo(10L);
		assertThat(posts.getFirst().shares()).isEqualTo(20L);
		assertThat(posts.getFirst().reposts()).isEqualTo(30L);
		assertThat(posts.getFirst().views()).isEqualTo(100L);
	}

	@Test
	void 단건_파싱_릴스는_6지표_전량() {
		// 단건은 /v2/media/info/by/code(media_or_ad 셰이프)로 이전됐다(08-04 — 구 by/code와 응답 동등성 실측).
		List<String> calls = new ArrayList<>();
		HikerClient client = new HikerClient(path -> {
			calls.add(path);
			return fixture("media-info-by-code.json");
		});
		PostInfo p = client.fetchPost("DbV7LgZsKG8");
		assertThat(calls).containsExactly("/v2/media/info/by/code?code=DbV7LgZsKG8");
		assertThat(p.contentType()).isEqualTo("REELS");
		assertThat(p.caption()).isNotNull();
		assertThat(p.likes()).isPositive();
		assertThat(p.comments()).isPositive();
		assertThat(p.views()).isPositive();
		assertThat(p.saves()).isPositive();
		assertThat(p.shares()).isPositive();
		assertThat(p.reposts()).isPositive();
		// post_meta 썸네일(계약 §3) — 단건 응답도 image_versions2.candidates[0].url에서 추출
		assertThat(p.thumbnailUrl()).isNotBlank();
		// POST 등록분 profile_meta 채움(트랙 II) — 단건 응답 user 노드에서 제로 콜로 추출
		assertThat(p.ownerFullName()).isEqualTo("Sephora");
		assertThat(p.ownerProfilePicUrl()).isNotBlank();
		// 소유 계정 IG pk(제로 콜) — POST 등록만 있는 계정의 clips 열거 재시도가 user_id로 쓴다
		assertThat(p.ownerUserId()).isEqualTo("23818608");
	}

	@Test
	void 단건_파싱_피드는_조회_저장_공유가_null() {
		HikerClient client = new HikerClient(path -> fixture("media-info-by-code-feed.json"));
		PostInfo p = client.fetchPost("DbOMP1_CY18");
		assertThat(p.contentType()).isEqualTo("FEED");
		assertThat(p.likes()).isPositive();
		assertThat(p.views()).isNull();
		assertThat(p.saves()).isNull();
		assertThat(p.shares()).isNull();
		// 트랙 II — 다른 픽스처(다른 계정)로도 owner 필드가 정상 채워지는지 재확인
		assertThat(p.ownerFullName()).isEqualTo("Rare Beauty by Selena Gomez");
		assertThat(p.ownerProfilePicUrl()).isNotBlank();
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

	/**
	 * 게시물 0건 계정 — Hiker는 열거할 엔트리가 없으면 200이 아니라 404
	 * {"detail":"Entries not found"}를 준다(릴스 0건 계정의 /v2/user/clips와 동일 규칙).
	 * collectAccount는 fetchProfile이 200으로 계정 존재를 확인한 직후에만 이 경로를 타므로,
	 * 여기서의 404는 계정 부재가 아니라 "열거할 게시물이 없음"이다 — 예외 대신 빈 리스트로 강등해야
	 * 등록(RegistrationService.registerAccount)이 404 SUBJECT_NOT_FOUND로 중단되지 않는다.
	 */
	@Test
	void 게시물_0건_계정은_medias_404를_빈_리스트로_강등한다() {
		HikerClient client = new HikerClient(path -> {
			if (path.startsWith("/v2/user/medias")) throw new SubjectNotFoundException("404 Entries not found");
			if (path.startsWith("/v2/user/clips")) return fixture("clips.json");
			throw new IllegalStateException("예상 밖 호출: " + path);
		});
		var posts = client.fetchRecentPosts("newuser", "999", 1);
		assertThat(posts).isEmpty();
	}

	/**
	 * 2페이지째(page>0) medias 404 — 계정 부재가 아니라 커서가 끝에 도달했다는 신호일 수 있다.
	 * 이미 1페이지에서 모은 결과가 있다면 그걸 버리지 않고 그대로 반환하며 열거만 중단한다
	 * (커서 미전진 가드와 같은 성격의 "조용한 종료" — 예외로 계정 전체 스윕을 실패시키지 않는다).
	 */
	@Test
	void 둘째페이지_medias_404는_1페이지_결과를_보존하고_중단한다() {
		HikerClient client = new HikerClient(path -> {
			if (path.startsWith("/v2/user/clips")) return fixture("clips.json");
			if (path.startsWith("/v2/user/medias")) {
				if (path.contains("page_id=")) throw new SubjectNotFoundException("404 Entries not found");
				return fixture("medias.json");
			}
			throw new IllegalStateException("예상 밖 호출: " + path);
		});
		var posts = client.fetchRecentPosts("rarebeauty", "3109786630", 2);
		assertThat(posts).hasSize(12);   // 1페이지 결과는 살아남는다
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

	// ── 좋아요 숨김(08-03) ──────────────────────────────────────────────────
	// 운영 raw.fetch_payload 실측: like_and_view_counts_disabled=true인 게시물은 like_count가
	// 실측이 아니라 프리뷰 잔여값(서로 다른 두 게시물이 똑같이 3)으로 잘려 온다. 그대로 저장하면
	// 화면에 83→3 급감으로 보이고 비공개 감지(값→null 전이)도 안 걸린다 — 취득 불가로 null 처리.

	@Test
	void 좋아요_숨김이면_like_count는_프리뷰_잔여값이라_null_처리한다() {
		HikerClient client = new HikerClient(path -> """
				{"media_or_ad":{"code":"Xx1","product_type":"clips","like_count":3,
				"like_and_view_counts_disabled":true,"comment_count":13,
				"play_count":1551,"ig_play_count":1551,"user":{"username":"acct"}},"status":"ok"}""");
		PostInfo p = client.fetchPost("Xx1");
		assertThat(p.likes()).isNull();
		// "숨김"과 "수집 실패"를 FE가 구분해야 해서 null과 별개로 플래그를 관통시킨다
		assertThat(p.likesHidden()).isTrue();
		// 숨김은 좋아요만 잘린다 — 댓글·조회수는 실측값이 계속 온다(운영 실측: ig_play_count 단조 증가)
		assertThat(p.comments()).isEqualTo(13L);
		assertThat(p.views()).isEqualTo(1551L);
	}

	@Test
	void 숨김_플래그가_없거나_false면_like_count를_그대로_쓴다() {
		HikerClient client = new HikerClient(path -> """
				{"media_or_ad":{"code":"Xx1","product_type":"clips","like_count":83,
				"like_and_view_counts_disabled":false,"user":{"username":"acct"}},"status":"ok"}""");
		PostInfo p = client.fetchPost("Xx1");
		assertThat(p.likes()).isEqualTo(83L);
		assertThat(p.likesHidden()).isFalse();
		assertThat(p.sharesHidden()).isFalse();
	}

	@Test
	void 공유_횟수_숨기기_토글은_share_count_disabled로_관측된다() {
		// 운영 실측(08-05): DaHSf2uB2Vj — 이 플래그 true인 게시물은 reshare_count가 영구 부재.
		HikerClient client = new HikerClient(path -> """
				{"media_or_ad":{"code":"Xx1","product_type":"clips","like_count":83,
				"share_count_disabled":true,"user":{"username":"acct"}},"status":"ok"}""");
		PostInfo p = client.fetchPost("Xx1");
		assertThat(p.sharesHidden()).isTrue();
		assertThat(p.likesHidden()).isFalse();   // 공유 숨김이 좋아요까지 숨기지는 않는다
	}

	@Test
	void 좋아요_숨김은_공유_숨김을_함의한다() {
		// IG 앱 문구 "좋아요 수 및 공유 횟수는 회원님만" + 실측: lvcd=true 10게시물 전원 공유 영구 부재.
		HikerClient client = new HikerClient(path -> """
				{"media_or_ad":{"code":"Xx1","product_type":"clips","like_count":3,
				"like_and_view_counts_disabled":true,"share_count_disabled":false,
				"user":{"username":"acct"}},"status":"ok"}""");
		PostInfo p = client.fetchPost("Xx1");
		assertThat(p.sharesHidden()).isTrue();
	}

	/** 단건은 usernameHint가 없어 user.username이 유일한 소유 계정 출처다 — 없으면 셰이프 이상. */
	@Test
	void 단건_응답에_소유_계정이_없으면_HikerFetch로() {
		HikerClient client = new HikerClient(path -> """
				{"media_or_ad":{"code":"Xx1","product_type":"clips","like_count":1},"status":"ok"}""");
		assertThatThrownBy(() -> client.fetchPost("Xx1"))
				.isInstanceOf(HikerFetchException.class);
	}

	@Test
	void 단건_응답에_media_or_ad가_없으면_SubjectNotFound로() {
		// 실존 부재는 전송 계층 404가 정상 경로 — 200인데 media_or_ad가 없는 건 부재로 강등한다.
		HikerClient client = new HikerClient(path -> "{\"status\":\"ok\"}");
		assertThatThrownBy(() -> client.fetchPost("gone"))
				.isInstanceOf(SubjectNotFoundException.class);
	}

	// ── 조회수 세션 일관성(08-02) ─────────────────────────────────────────────
	// 운영 raw.fetch_payload 실측: Hiker가 콜마다 다른 IG 세션을 태우는데, FB 교차게시 데이터가
	// 보이는 세션에서는 play_count = ig_play_count + fb_play_count(합산)이고, 아닌 세션에서는
	// fb_play_count 키가 아예 없고 play_count == ig_play_count다. 그래서 play_count를 우선하면
	// 같은 게시물 조회수가 콜마다 오르내린다(DX0U76Xy1D2: 221 → 305 → 222 역행 실측).
	// ig_play_count는 전 콜에서 존재했고 단조 증가 — 조회수의 정본은 ig_play_count다.

	@Test
	void 단건_조회수는_세션따라_흔들리는_play_count가_아니라_ig_play_count를_쓴다() {
		HikerClient client = new HikerClient(path -> """
				{"media_or_ad":{"code":"Xx1","product_type":"clips","like_count":1,
				"play_count":305,"ig_play_count":222,"fb_play_count":83,"user":{"username":"acct"}},"status":"ok"}""");
		PostInfo p = client.fetchPost("Xx1");
		assertThat(p.views()).isEqualTo(222L);
		assertThat(p.fbPlays()).isEqualTo(83L);   // FB 몫은 별도 보존 — 저장 시 views 합산 재료
	}

	/** fb 키 부재(IG 전용 세션)와 fb=0(합산 세션이지만 FB 재생 0)은 다르다 — 캐리포워드·재시도 판정 기준. */
	@Test
	void fb_play_count_키_부재는_null이고_0은_0이다() {
		HikerClient client = new HikerClient(path -> """
				{"media_or_ad":{"code":"Xx1","product_type":"clips","like_count":1,
				"play_count":222,"ig_play_count":222,"user":{"username":"acct"}},"status":"ok"}""");
		assertThat(client.fetchPost("Xx1").fbPlays()).isNull();

		HikerClient zero = new HikerClient(path -> """
				{"media_or_ad":{"code":"Xx1","product_type":"clips","like_count":1,
				"play_count":222,"ig_play_count":222,"fb_play_count":0,"user":{"username":"acct"}},"status":"ok"}""");
		assertThat(zero.fetchPost("Xx1").fbPlays()).isEqualTo(0L);
	}

	/**
	 * fb 키 없이도 합산 play를 주는 세션이 있다(운영 실측 DUrj0iGEn6G: play 570,331 vs ig 512,077,
	 * fb 키 부재) — play > ig면 fb 몫을 play - ig로 유도해 관측으로 인정한다. fb 키가 있을 때
	 * play - ig == fb임은 실측 검산됨(305-222=83 등). fb 키가 0인데 play > ig인 모순 응답
	 * (DPQoGI1APa_)도 화면값(play) 기준으로 유도값을 우선한다.
	 */
	@Test
	void fb_키가_없어도_play가_ig보다_크면_차이를_fb몫으로_유도한다() {
		HikerClient client = new HikerClient(path -> """
				{"media_or_ad":{"code":"Xx1","product_type":"clips","like_count":1,
				"play_count":570331,"ig_play_count":512077,"user":{"username":"acct"}},"status":"ok"}""");
		PostInfo p = client.fetchPost("Xx1");
		assertThat(p.views()).isEqualTo(512077L);
		assertThat(p.fbPlays()).isEqualTo(58254L);
	}

	@Test
	void fb_키가_0이어도_play가_ig보다_크면_유도값을_우선한다() {
		HikerClient client = new HikerClient(path -> """
				{"media_or_ad":{"code":"Xx1","product_type":"clips","like_count":1,
				"play_count":916881,"ig_play_count":869438,"fb_play_count":0,"user":{"username":"acct"}},"status":"ok"}""");
		assertThat(client.fetchPost("Xx1").fbPlays()).isEqualTo(47443L);
	}

	/** 방어: ig 키가 없는 응답이 오면 play - fb로 IG 몫을 복원한다(합산 이중 계상 방지). */
	@Test
	void ig_play_count가_없으면_play에서_fb를_빼서_IG_몫을_복원한다() {
		HikerClient client = new HikerClient(path -> """
				{"media_or_ad":{"code":"Xx1","product_type":"clips","like_count":1,
				"play_count":305,"fb_play_count":83,"user":{"username":"acct"}},"status":"ok"}""");
		PostInfo p = client.fetchPost("Xx1");
		assertThat(p.views()).isEqualTo(222L);
		assertThat(p.fbPlays()).isEqualTo(83L);
	}

	@Test
	void 열거_클립_머지_조회수도_ig_play_count를_쓰고_fb몫도_머지된다() {
		HikerClient client = new HikerClient(path -> {
			if (path.startsWith("/v2/user/clips")) {
				return """
						{"response":{"items":[{"media":{"code":"ReelA","product_type":"clips",
						"play_count":305,"ig_play_count":222,"fb_play_count":83}}],
						"paging_info":{"more_available":false}},"next_page_id":null}""";
			}
			return """
					{"response":{"items":[{"code":"ReelA","taken_at":1700000000,"product_type":"clips",
					"like_count":1,"comment_count":1,"media_repost_count":1}],
					"more_available":false},"next_page_id":null}""";
		});
		var posts = client.fetchRecentPosts("acct", "999", 1);
		assertThat(posts).hasSize(1);
		assertThat(posts.getFirst().views()).isEqualTo(222L);
		assertThat(posts.getFirst().fbPlays()).isEqualTo(83L);
	}

	/** 클립 콜만 따로 재조회하는 재시도 경로용 — 코드별 IG·FB 몫을 그대로 돌려준다. */
	@Test
	void fetchClipCounts는_코드별_IG_FB_몫을_준다() {
		HikerClient client = new HikerClient(path -> """
				{"response":{"items":[
				{"media":{"code":"ReelA","play_count":305,"ig_play_count":222,"fb_play_count":83}},
				{"media":{"code":"ReelB","play_count":10,"ig_play_count":10}}],
				"paging_info":{"more_available":false}},"next_page_id":null}""");
		var counts = client.fetchClipCounts("999", 1);
		assertThat(counts.get("ReelA").igPlays()).isEqualTo(222L);
		assertThat(counts.get("ReelA").fbPlays()).isEqualTo(83L);
		assertThat(counts.get("ReelB").igPlays()).isEqualTo(10L);
		assertThat(counts.get("ReelB").fbPlays()).isNull();
	}

	// ── 댓글(§10-1) ──────────────────────────────────────────────────────────

	/**
	 * 픽스처 comments.json — 6건 중 3건은 preview_child_comments의 is_created_by_media_owner==true로
	 * 작성자 본인 답글이 잡힌다(협업 게시물이라 실제 owner는 sephora — postUsername="rarebeauty"로 불러도
	 * is_created_by_media_owner 경로가 잡히는지가 이 테스트의 핵심).
	 */
	@Test
	void 댓글_파싱_6건_owner_답글_판정() {
		HikerClient client = new HikerClient(path -> fixture("comments.json"));
		var comments = client.fetchComments("DbV7LgZsKG8", "rarebeauty", 1);

		assertThat(comments).hasSize(6);
		assertThat(comments).filteredOn(c -> c.ownerReplyText() != null).hasSize(3);

		var withReply = comments.stream().filter(c -> c.id().equals("18197804143375437")).findFirst().orElseThrow();
		assertThat(withReply.author()).isEqualTo("colorowyy");
		assertThat(withReply.body()).isEqualTo("IT WAS AMAZING EXPERIENCE 💚");
		assertThat(withReply.likeCount()).isEqualTo(84L);
		assertThat(withReply.commentedAt()).isEqualTo(Instant.ofEpochSecond(1785255052L));
		assertThat(withReply.ownerReplyText()).contains("so glad you were a part of it");

		var withoutReply = comments.stream()
				.filter(c -> c.id().equals("18197038627373746")).findFirst().orElseThrow();
		assertThat(withoutReply.ownerReplyText()).isNull();
	}

	/** 협업 게시물이 아닌 일반 케이스 — is_created_by_media_owner 없이 username 일치만으로도 답글이 잡혀야 한다. */
	private static final String USERNAME_MATCH_COMMENTS = """
			{"response":{"comments":[{"pk":"1","text":"댓글","comment_like_count":1,
			"created_at_utc":1700000000,"user":{"username":"fan1"},
			"preview_child_comments":[{"is_created_by_media_owner":false,
			"user":{"username":"RareBeauty"},"text":"고마워요"}]}]},"has_more_comments":false,
			"next_page_id":null}""";

	@Test
	void owner_답글_판정은_username_대소문자_무시_일치도_잡는다() {
		HikerClient client = new HikerClient(path -> USERNAME_MATCH_COMMENTS);
		var comments = client.fetchComments("DbV7LgZsKG8", "rarebeauty", 1);

		assertThat(comments).hasSize(1);
		assertThat(comments.getFirst().ownerReplyText()).isEqualTo("고마워요");
	}

	/** 필수 필드(pk·text·좋아요·작성 시각·작성자) 중 하나라도 빠지면 저장 대상에서 제외한다. */
	private static final String MISSING_FIELD_COMMENTS = """
			{"response":{"comments":[
			  {"pk":"1","text":"정상","comment_like_count":1,"created_at_utc":1700000000,"user":{"username":"ok"}},
			  {"text":"pk없음","comment_like_count":1,"created_at_utc":1700000000,"user":{"username":"x"}},
			  {"pk":"3","comment_like_count":1,"created_at_utc":1700000000,"user":{"username":"x"}},
			  {"pk":"4","text":"좋아요없음","created_at_utc":1700000000,"user":{"username":"x"}},
			  {"pk":"5","text":"시간없음","comment_like_count":1,"user":{"username":"x"}},
			  {"pk":"6","text":"작성자없음","comment_like_count":1,"created_at_utc":1700000000}
			]},"has_more_comments":false,"next_page_id":null}""";

	@Test
	void 결손_필드_댓글은_제외된다() {
		HikerClient client = new HikerClient(path -> MISSING_FIELD_COMMENTS);
		var comments = client.fetchComments("DbV7LgZsKG8", "rarebeauty", 1);

		assertThat(comments).hasSize(1);
		assertThat(comments.getFirst().id()).isEqualTo("1");
	}

	// ── 댓글 다중 페이지(07-31, comment-pages 1→3 상향) ─────────────────────────
	// comment-pages가 1이던 동안은 2페이지 이후 커서 경로가 테스트도 없고 운영에서 돈 적도 없다.
	// 3으로 올리는 이번 변경이 그 경로를 처음 타므로 열거(medias) 쪽과 대칭으로 반드시 덮는다.

	private static final String COMMENTS_PAGE1 = """
			{"response":{"comments":[
			{"pk":"101","text":"1페이지 댓글","comment_like_count":1,
			 "created_at_utc":1700000001,"user":{"username":"fan1"},"preview_child_comments":[]}
			],"has_more_comments":true},"next_page_id":"cmt-cursor-2"}""";

	private static final String COMMENTS_PAGE2 = """
			{"response":{"comments":[
			{"pk":"102","text":"2페이지 댓글","comment_like_count":2,
			 "created_at_utc":1700000002,"user":{"username":"fan2"},"preview_child_comments":[]}
			],"has_more_comments":false},"next_page_id":null}""";

	@Test
	void 댓글_2페이지는_next_page_id를_커서로_전달하고_다음_페이지를_이어붙인다() {
		List<String> calls = new ArrayList<>();
		HikerClient client = new HikerClient(path -> {
			calls.add(path);
			return path.contains("page_id=") ? COMMENTS_PAGE2 : COMMENTS_PAGE1;
		});

		var comments = client.fetchComments("DbV7LgZsKG8", "rarebeauty", 2);

		assertThat(comments).extracting(CommentInfo::id).containsExactly("101", "102");
		assertThat(calls).hasSize(2);
		assertThat(calls.get(0)).doesNotContain("page_id");
		assertThat(calls.get(1)).contains("&page_id=cmt-cursor-2");
	}

	/**
	 * has_more_comments는 신뢰하지 않는다(07-31 defect A) — 운영 실측(media 3929190553799320931,
	 * 댓글 2,325건)에서 1페이지가 has_more_comments=false를 주면서도 next_page_id를 들고 있었고,
	 * 그 커서로 재요청한 2페이지에서 1페이지와 중복 0건인 신규 댓글이 나왔다(플래그가 거짓말).
	 * 그래서 has_more_comments=false여도 next_page_id가 있으면 요청한 페이지 수만큼 계속 가져와야 한다.
	 */
	@Test
	void has_more_comments가_false여도_next_page_id가_있으면_계속_가져온다() {
		List<String> calls = new ArrayList<>();
		HikerClient client = new HikerClient(path -> {
			calls.add(path);
			if (path.contains("page_id=")) {
				return """
						{"response":{"comments":[
						{"pk":"2","text":"2페이지 댓글","comment_like_count":1,
						 "created_at_utc":1700000001,"user":{"username":"fan2"},"preview_child_comments":[]}
						],"has_more_comments":false},"next_page_id":null}""";
			}
			return """
					{"response":{"comments":[
					{"pk":"1","text":"1페이지 댓글","comment_like_count":1,
					 "created_at_utc":1700000000,"user":{"username":"fan1"},"preview_child_comments":[]}
					],"has_more_comments":false},"next_page_id":"cmt-cursor-2"}""";
		});

		var comments = client.fetchComments("DbV7LgZsKG8", "rarebeauty", 3);

		assertThat(comments).extracting(CommentInfo::id).containsExactly("1", "2");
		assertThat(calls).hasSize(2);   // has_more_comments=false에 속지 않고 커서를 따라 2페이지까지 갔다
	}

	/** 요청한 페이지 수(예: 2)에 도달하면 응답이 계속 더 있다고 해도(has_more_comments=true) 멈춘다. */
	@Test
	void 요청한_페이지_수에_도달하면_next_page_id가_남아도_멈춘다() {
		List<String> calls = new ArrayList<>();
		HikerClient client = new HikerClient(path -> {
			calls.add(path);
			int page = calls.size();
			return """
					{"response":{"comments":[
					{"pk":"%d","text":"댓글%d","comment_like_count":1,
					 "created_at_utc":1700000000,"user":{"username":"fan"},"preview_child_comments":[]}
					],"has_more_comments":true},"next_page_id":"cmt-cursor-%d"}""".formatted(page, page, page);
		});

		var comments = client.fetchComments("DbV7LgZsKG8", "rarebeauty", 2);

		assertThat(calls).hasSize(2);   // 3페이지째도 있다고 응답해도 요청한 2에서 멈춘다
		assertThat(comments).hasSize(2);
	}

	/** 무진전 가드 — 2페이지째에서 유효 댓글이 0건이면(전부 결손 필드) 남은 페이지를 더 부르지 않는다. */
	@Test
	void 댓글_커서가_전진하지_않으면_경고하고_중단한다() {
		List<String> calls = new ArrayList<>();
		HikerClient client = new HikerClient(path -> {
			calls.add(path);
			if (path.contains("page_id=")) {
				// 2페이지째: comments 배열은 있지만 전부 결손 필드라 유효 댓글 0건 — 새 댓글 없음
				return """
						{"response":{"comments":[
						{"text":"pk없음","comment_like_count":1,"created_at_utc":1700000000,"user":{"username":"x"}}
						],"has_more_comments":true},"next_page_id":"cmt-cursor-3"}""";
			}
			return COMMENTS_PAGE1;   // has_more_comments:true, next_page_id:"cmt-cursor-2"
		});

		var comments = client.fetchComments("DbV7LgZsKG8", "rarebeauty", 5);

		assertThat(comments).hasSize(1);   // 1페이지 유효 댓글 1건만
		assertThat(calls).hasSize(2);      // 2페이지째에서 신규 0건 감지, 5페이지 요청해도 중단
	}

	// ── share 해소(§10-2) ────────────────────────────────────────────────────

	@Test
	void 단축링크_해소_파싱() {
		HikerClient client = new HikerClient(path -> fixture("media-info-by-url.json"));
		MediaRef ref = client.resolveMediaByUrl("https://www.instagram.com/reel/DbV7LgZsKG8/");

		assertThat(ref.shortCode()).isEqualTo("DbV7LgZsKG8");
		assertThat(ref.username()).isEqualTo("sephora");
		assertThat(ref.contentType()).isEqualTo("REELS");
	}

	@Test
	void 단축링크_해소_400은_ShareLinkUnresolved로() {
		HikerClient client = new HikerClient(path -> {
			throw new HikerBadRequestException("400");
		});
		assertThatThrownBy(() -> client.resolveMediaByUrl("https://www.instagram.com/share/reel/bad/"))
				.isInstanceOf(ShareLinkUnresolvedException.class);
	}

	@Test
	void 단축링크_해소_404는_SubjectNotFound로() {
		HikerClient client = new HikerClient(path -> {
			throw new SubjectNotFoundException("404");
		});
		assertThatThrownBy(() -> client.resolveMediaByUrl("https://www.instagram.com/reel/gone/"))
				.isInstanceOf(SubjectNotFoundException.class);
	}
}

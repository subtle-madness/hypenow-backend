package com.celfit.was.v1.brandmonitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandHashtagTagRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 해시태그 발견 게시물 조립 규칙 단위 고정(스펙 §8, 별도 탭 결정 2026-08-12; 2026-08-18 direct
 * 통합 §T11로 판정 산지 재배선) — 리포지토리 없이 row record를 손으로 만들어 순수 변환만 검증한다.
 * 배선(소유권·404)은 컨트롤러 슬라이스 테스트가 덮는다.
 */
class BrandHashtagPostAssemblerTest {

	@Test
	void postUrl은_콘텐츠_타입과_무관하게_항상_p_경로다() {
		var reels = BrandHashtagPostAssembler.toResponse(hashtagRow("HHH", "REELS"));
		var feed = BrandHashtagPostAssembler.toResponse(hashtagRow("III", "FEED"));

		assertThat(reels.postUrl()).isEqualTo("https://www.instagram.com/p/HHH/");
		assertThat(reels.contentType()).isEqualTo("reels");
		assertThat(feed.postUrl()).isEqualTo("https://www.instagram.com/p/III/");
		assertThat(feed.contentType()).isEqualTo("feed");
	}

	@Test
	void contentType_불명은_피드로_접는다() {
		var post = BrandHashtagPostAssembler.toResponse(hashtagRow("HHH", null));

		assertThat(post.contentType()).isEqualTo("feed");
	}

	@Test
	void 캡션과_author_지표는_열거_행에서_그대로_옮긴다() {
		var post = BrandHashtagPostAssembler.toResponse(hashtagRow("HHH", "REELS"));

		assertThat(post.shortcode()).isEqualTo("HHH");
		assertThat(post.matchedTag()).isEqualTo("#브랜드명");
		assertThat(post.caption()).isEqualTo("해시태그 캡션");
		assertThat(post.thumbnailUrl()).isEqualTo("https://cdn/hashtag-thumb.jpg");
		assertThat(post.authorUsername()).isEqualTo("hashtag_influencer");
		assertThat(post.authorFullName()).isEqualTo("해시태그 인플루언서");
		assertThat(post.authorProfilePicUrl()).isEqualTo("https://cdn/hashtag-author.jpg");
		assertThat(post.authorProfileUrl()).isEqualTo("https://www.instagram.com/hashtag_influencer/");
		assertThat(post.likes()).isEqualTo(20L);
		assertThat(post.comments()).isEqualTo(3L);
		assertThat(post.takenAt()).isEqualTo("2026-08-06T10:00:00+09:00");
		assertThat(post.firstSeenAt()).isEqualTo("2026-08-06T11:00:00+09:00");
		assertThat(post.brandPostId()).isNull();
	}

	@Test
	void 협찬은_캡션_확정_키워드로만_판정한다() {
		var sponsored = BrandHashtagPostAssembler.toResponse(hashtagRow("HHH", "REELS", "오늘의 #협찬 후기"));
		var organic = BrandHashtagPostAssembler.toResponse(hashtagRow("III", "REELS", "그냥 일상 기록"));

		assertThat(sponsored.sponsorship()).isEqualTo("sponsored");
		assertThat(organic.sponsorship()).isEqualTo("unknown");
	}

	@Test
	void 무효_스킴_이미지_URL은_null로_강등한다() {
		var row = new BrandReadRepository.BrandHashtagPostRow("HHH", "#브랜드명", "hashtag_influencer",
				"해시태그 인플루언서", "javascript:alert(1)", OffsetDateTime.parse("2026-08-06T01:00:00Z"),
				"캡션", "REELS", "data:image/png;base64,AAAA", 20L, 3L,
				OffsetDateTime.parse("2026-08-06T02:00:00Z"), null, null);

		var post = BrandHashtagPostAssembler.toResponse(row);

		assertThat(post.authorProfilePicUrl()).isNull();
		assertThat(post.thumbnailUrl()).isNull();
	}

	@Test
	void author_username이_없으면_프로필_URL도_null이다() {
		var row = new BrandReadRepository.BrandHashtagPostRow("HHH", "#브랜드명", null, null, null,
				OffsetDateTime.parse("2026-08-06T01:00:00Z"), "캡션", "REELS", null, null, null,
				OffsetDateTime.parse("2026-08-06T02:00:00Z"), null, null);

		var post = BrandHashtagPostAssembler.toResponse(row);

		assertThat(post.authorUsername()).isNull();
		assertThat(post.authorProfileUrl()).isNull();
		assertThat(post.likes()).isNull();
		assertThat(post.comments()).isNull();
	}

	/** image_object_path(monitoring 자체 아카이브 결과)가 있으면 원본 CDN URL보다 그걸 우선 서빙한다. */
	@Test
	void 아카이브된_썸네일은_img_상대경로를_우선_서빙한다() {
		var row = new BrandReadRepository.BrandHashtagPostRow("HHH", "#브랜드명", "hashtag_influencer",
				"해시태그 인플루언서", "https://cdn/hashtag-author.jpg",
				OffsetDateTime.parse("2026-08-06T01:00:00Z"), "캡션", "REELS",
				"https://cdn/hashtag-thumb.jpg", 20L, 3L, OffsetDateTime.parse("2026-08-06T02:00:00Z"),
				"monitor-hashtag-post/HHH.jpg", null);

		var post = BrandHashtagPostAssembler.toResponse(row);

		assertThat(post.thumbnailUrl()).isEqualTo("/img/monitor-hashtag-post/HHH.jpg");
		// 작성자 프로필 사진은 미아카이브(authorImageObjectPath null)라 원본 CDN URL 폴백.
		assertThat(post.authorProfilePicUrl()).isEqualTo("https://cdn/hashtag-author.jpg");
	}

	// ---------- 작성자 프로필 사진 아카이브(2026-08-17 신설) ----------

	/** authorImageObjectPath(monitoring 자체 아카이브 결과)가 있으면 원본 CDN URL보다 그걸 우선 서빙한다. */
	@Test
	void 아카이브된_작성자_프로필_사진은_img_상대경로를_우선_서빙한다() {
		var row = new BrandReadRepository.BrandHashtagPostRow("HHH", "#브랜드명", "hashtag_influencer",
				"해시태그 인플루언서", "https://cdn/hashtag-author.jpg",
				OffsetDateTime.parse("2026-08-06T01:00:00Z"), "캡션", "REELS",
				"https://cdn/hashtag-thumb.jpg", 20L, 3L, OffsetDateTime.parse("2026-08-06T02:00:00Z"),
				null, "monitor-hashtag-author/hashtag_influencer.jpg");

		var post = BrandHashtagPostAssembler.toResponse(row);

		assertThat(post.authorProfilePicUrl()).isEqualTo("/img/monitor-hashtag-author/hashtag_influencer.jpg");
	}

	/** 미아카이브(authorImageObjectPath null)면 아카이브 전 신규 발견분도 깨지지 않게 원본 CDN URL로 폴백한다. */
	@Test
	void 미아카이브_작성자_프로필_사진은_원본_CDN_URL로_폴백한다() {
		var row = new BrandReadRepository.BrandHashtagPostRow("HHH", "#브랜드명", "hashtag_influencer",
				"해시태그 인플루언서", "https://cdn/hashtag-author.jpg",
				OffsetDateTime.parse("2026-08-06T01:00:00Z"), "캡션", "REELS",
				"https://cdn/hashtag-thumb.jpg", 20L, 3L, OffsetDateTime.parse("2026-08-06T02:00:00Z"),
				null, null);

		var post = BrandHashtagPostAssembler.toResponse(row);

		assertThat(post.authorProfilePicUrl()).isEqualTo("https://cdn/hashtag-author.jpg");
	}

	// ---------- 승격 상태 필드(brandPostId, 2026-08-17 신설 · 2026-08-18 direct 통합 재배선 ·
	// 08-19 등록자 스코프 개정) ----------

	private static final long USER_ID = 7L;

	@Test
	void 내가_direct_등록했으면_brandPostId가_채워진다() {
		var brandReadRepository = mock(BrandReadRepository.class);
		var directPostRepository = mock(BrandDirectPostRepository.class);
		given(brandReadRepository.findHashtagPosts(eq(1L), any(), anyInt()))
				.willReturn(List.of(hashtagRowWithAuthor("HHH", "hashtag_influencer")));
		given(brandReadRepository.findBrandPoolStatus(eq(1L), any()))
				.willReturn(List.of(poolStatus("HHH", false, true)));
		given(directPostRepository.shortCodesByUser(USER_ID)).willReturn(Set.of("HHH"));

		var assembler = new BrandHashtagPostAssembler(brandReadRepository, directPostRepository,
				mock(BrandHashtagTagRepository.class));
		List<BrandHashtagPostResponse> result = assembler.assembleForBrand(USER_ID, 1L);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).brandPostId()).isEqualTo("HHH");
	}

	/**
	 * 등록자 스코프 개정(요구사항, 08-19) — direct_registered_at은 브랜드 단위 컬럼이라 "누군가"
	 * 등록했다는 뜻뿐이다. 등록자 원장(shortCodesByUser)에 이 유저가 없으면(=다른 유저가 등록함)
	 * brandPostId는 null이어야 한다 — 남이 수집한 상태가 내 화면에 "수집됨"으로 보이면 안 된다.
	 */
	@Test
	void 남이_direct_등록했으면_brandPostId는_null이다() {
		var brandReadRepository = mock(BrandReadRepository.class);
		var directPostRepository = mock(BrandDirectPostRepository.class);
		given(brandReadRepository.findHashtagPosts(eq(1L), any(), anyInt()))
				.willReturn(List.of(hashtagRowWithAuthor("HHH", "hashtag_influencer")));
		given(brandReadRepository.findBrandPoolStatus(eq(1L), any()))
				.willReturn(List.of(poolStatus("HHH", false, true)));
		// shortCodesByUser 미스텁 — Mockito 기본값 empty(내가 등록한 적 없음).

		var assembler = new BrandHashtagPostAssembler(brandReadRepository, directPostRepository,
				mock(BrandHashtagTagRepository.class));
		List<BrandHashtagPostResponse> result = assembler.assembleForBrand(USER_ID, 1L);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).brandPostId()).isNull();
	}

	/** direct 등록 행이 하나도 없으면 원장 조회(shortCodesByUser) 자체를 생략한다(불필요한 조회 방지). */
	@Test
	void direct_등록_행이_없으면_원장_조회를_생략한다() {
		var brandReadRepository = mock(BrandReadRepository.class);
		var directPostRepository = mock(BrandDirectPostRepository.class);
		given(brandReadRepository.findHashtagPosts(eq(1L), any(), anyInt()))
				.willReturn(List.of(hashtagRowWithAuthor("HHH", "hashtag_influencer")));
		given(brandReadRepository.findBrandPoolStatus(eq(1L), any()))
				.willReturn(List.of(poolStatus("HHH", false, false)));

		var assembler = new BrandHashtagPostAssembler(brandReadRepository, directPostRepository,
				mock(BrandHashtagTagRepository.class));
		assembler.assembleForBrand(USER_ID, 1L);

		verify(directPostRepository, never()).shortCodesByUser(anyLong());
	}

	/**
	 * 2026-08-18 direct 통합 이후 브랜드 풀 상태는 브랜드 스코프다 — 다른 브랜드의 direct 등록이라는
	 * 개념 자체가 없다(조회 자체가 이 brandId로 스코프된다). 이 브랜드 풀에 없는 shortcode(조회 결과에
	 * 없음)는 poolStatus 맵에 없으므로 brandPostId가 null이다.
	 */
	@Test
	void 브랜드_풀에_없는_shortcode는_brandPostId가_null이다() {
		var brandReadRepository = mock(BrandReadRepository.class);
		var directPostRepository = mock(BrandDirectPostRepository.class);
		given(brandReadRepository.findHashtagPosts(eq(1L), any(), anyInt()))
				.willReturn(List.of(hashtagRowWithAuthor("HHH", "hashtag_influencer")));
		given(brandReadRepository.findBrandPoolStatus(eq(1L), any())).willReturn(List.of());

		var assembler = new BrandHashtagPostAssembler(brandReadRepository, directPostRepository,
				mock(BrandHashtagTagRepository.class));
		List<BrandHashtagPostResponse> result = assembler.assembleForBrand(USER_ID, 1L);

		assertThat(result.get(0).brandPostId()).isNull();
	}

	@Test
	void 취소로_direct_등록이_풀리면_brandPostId는_다시_null이다() {
		var brandReadRepository = mock(BrandReadRepository.class);
		var directPostRepository = mock(BrandDirectPostRepository.class);
		given(brandReadRepository.findHashtagPosts(eq(1L), any(), anyInt()))
				.willReturn(List.of(hashtagRowWithAuthor("HHH", "hashtag_influencer")));
		given(brandReadRepository.findBrandPoolStatus(eq(1L), any()))
				.willReturn(List.of(poolStatus("HHH", false, false)));   // 취소 후 — 행은 남되 direct 해제

		var assembler = new BrandHashtagPostAssembler(brandReadRepository, directPostRepository,
				mock(BrandHashtagTagRepository.class));
		List<BrandHashtagPostResponse> result = assembler.assembleForBrand(USER_ID, 1L);

		assertThat(result.get(0).brandPostId()).isNull();
	}

	/**
	 * 2026-08-18 정정: 이 화면은 "태그 안 된 게시물"이라 이미 tagged로 측정 중인 shortcode는
	 * 발견 목록 자체에서 빠진다(제외 조건: tag_detected AND NOT direct_registered). 이 제외는
	 * 브랜드 공유 판정이라(해시태그 감지 데이터 자체는 공유 유지) 등록자 원장과 무관하다.
	 */
	@Test
	void tagged로만_존재하면_발견_목록에서_제외된다() {
		var brandReadRepository = mock(BrandReadRepository.class);
		var directPostRepository = mock(BrandDirectPostRepository.class);
		given(brandReadRepository.findHashtagPosts(eq(1L), any(), anyInt()))
				.willReturn(List.of(hashtagRowWithAuthor("HHH", "hashtag_influencer")));
		given(brandReadRepository.findBrandPoolStatus(eq(1L), any()))
				.willReturn(List.of(poolStatus("HHH", true, false)));

		var assembler = new BrandHashtagPostAssembler(brandReadRepository, directPostRepository,
				mock(BrandHashtagTagRepository.class));
		List<BrandHashtagPostResponse> result = assembler.assembleForBrand(USER_ID, 1L);

		assertThat(result).isEmpty();
	}

	/**
	 * direct 등록이 살아 있으면 같은 shortcode가 tagged로도 겹쳐도(사진 태그+해시태그 동시 게시물)
	 * 목록에서 빠지지 않는다 — direct가 우선이라는 승격분 dim 잔존 계약(2026-08-18). 제외 여부는
	 * 여전히 브랜드 스코프고(누구나 이 행을 본다), brandPostId만 등록자 스코프다.
	 */
	@Test
	void direct_등록이_있으면_tagged_겹침이어도_목록에_남고_내가_등록했으면_brandPostId가_채워진다() {
		var brandReadRepository = mock(BrandReadRepository.class);
		var directPostRepository = mock(BrandDirectPostRepository.class);
		given(brandReadRepository.findHashtagPosts(eq(1L), any(), anyInt()))
				.willReturn(List.of(hashtagRowWithAuthor("HHH", "hashtag_influencer")));
		given(brandReadRepository.findBrandPoolStatus(eq(1L), any()))
				.willReturn(List.of(poolStatus("HHH", true, true)));
		given(directPostRepository.shortCodesByUser(USER_ID)).willReturn(Set.of("HHH"));

		var assembler = new BrandHashtagPostAssembler(brandReadRepository, directPostRepository,
				mock(BrandHashtagTagRepository.class));
		List<BrandHashtagPostResponse> result = assembler.assembleForBrand(USER_ID, 1L);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).brandPostId()).isEqualTo("HHH");
	}

	/** direct도 tagged도 아닌 순수 발견 행은 그대로 남고 brandPostId는 null이다. */
	@Test
	void 순수_발견_행은_목록에_남고_brandPostId는_null이다() {
		var brandReadRepository = mock(BrandReadRepository.class);
		var directPostRepository = mock(BrandDirectPostRepository.class);
		given(brandReadRepository.findHashtagPosts(eq(1L), any(), anyInt()))
				.willReturn(List.of(hashtagRowWithAuthor("HHH", "hashtag_influencer")));
		given(brandReadRepository.findBrandPoolStatus(eq(1L), any()))
				.willReturn(List.of(poolStatus("HHH", false, false)));

		var assembler = new BrandHashtagPostAssembler(brandReadRepository, directPostRepository,
				mock(BrandHashtagTagRepository.class));
		List<BrandHashtagPostResponse> result = assembler.assembleForBrand(USER_ID, 1L);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).brandPostId()).isNull();
	}

	/** 제외 문자열 기능 폐기(2026-08-17)로 게시자 username과 무관하게 조회된 발견분이 전부 남는다. */
	@Test
	void 제외_문자열_기능_폐기로_계정명_포함_작성자도_남는다() {
		var repository = mock(BrandReadRepository.class);
		var directPostRepository = mock(BrandDirectPostRepository.class);
		given(repository.findHashtagPosts(eq(1L), any(), anyInt()))
				.willReturn(List.of(hashtagRowWithAuthor("HHH", "cclime_official_staff")));
		given(repository.findBrandPoolStatus(eq(1L), any())).willReturn(List.of());

		var assembler = new BrandHashtagPostAssembler(repository, directPostRepository, mock(BrandHashtagTagRepository.class));
		List<BrandHashtagPostResponse> result = assembler.assembleForBrand(USER_ID, 1L);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).shortcode()).isEqualTo("HHH");
	}

	// ---------- 내 태그 매칭 필터(요구사항, 08-19 확장) ----------

	/** 조회자가 관리하는 태그와 매칭된 게시물만 남고, 다른 태그에만 매칭된 게시물은 빠진다. */
	@Test
	void 내_태그에_매칭된_게시물만_노출된다() {
		var repository = mock(BrandReadRepository.class);
		var directPostRepository = mock(BrandDirectPostRepository.class);
		var hashtagTagRepository = mock(BrandHashtagTagRepository.class);
		given(repository.findHashtagPosts(eq(1L), any(), anyInt())).willReturn(List.of(
				hashtagRowWithAuthor("HHH", "poster1"), hashtagRowWithAuthor("III", "poster2")));
		given(repository.findBrandPoolStatus(eq(1L), any())).willReturn(List.of());
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, 1L)).willReturn(Set.of("cclime"));
		given(repository.findMatchedTags(eq(1L), any())).willReturn(List.of(
				new BrandReadRepository.MatchedTagRow("HHH", "cclime"),
				new BrandReadRepository.MatchedTagRow("III", "다른브랜드")));

		var assembler = new BrandHashtagPostAssembler(repository, directPostRepository, hashtagTagRepository);
		List<BrandHashtagPostResponse> result = assembler.assembleForBrand(USER_ID, 1L);

		assertThat(result).extracting(BrandHashtagPostResponse::shortcode).containsExactly("HHH");
	}

	/**
	 * 매칭 기록이 아예 없는 행(마이그레이션 백필 이전 데이터 등)은 fail-open이다 — 내 태그가
	 * 있어도(=필터가 활성) 매칭 정보 자체가 없으면 숨기지 않고 노출한다(요구사항, 08-19).
	 */
	@Test
	void 매칭_기록이_없으면_fail_open으로_노출된다() {
		var repository = mock(BrandReadRepository.class);
		var directPostRepository = mock(BrandDirectPostRepository.class);
		var hashtagTagRepository = mock(BrandHashtagTagRepository.class);
		given(repository.findHashtagPosts(eq(1L), any(), anyInt()))
				.willReturn(List.of(hashtagRowWithAuthor("HHH", "poster1")));
		given(repository.findBrandPoolStatus(eq(1L), any())).willReturn(List.of());
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, 1L)).willReturn(Set.of("cclime"));
		given(repository.findMatchedTags(eq(1L), any())).willReturn(List.of());   // 매칭 기록 없음

		var assembler = new BrandHashtagPostAssembler(repository, directPostRepository, hashtagTagRepository);
		List<BrandHashtagPostResponse> result = assembler.assembleForBrand(USER_ID, 1L);

		assertThat(result).extracting(BrandHashtagPostResponse::shortcode).containsExactly("HHH");
	}

	/**
	 * 시딩 전 정합성(요구사항, 08-19) — 조회자 본인의 태그 원장이 이 브랜드에 대해 비어 있으면(아직
	 * 태그 관리 API를 건드린 적 없음) 필터 자체를 건너뛰고 전원 노출한다 — 기능 출시 직후의 회귀 방지.
	 */
	@Test
	void 내_태그_원장이_비어있으면_필터를_건너뛰고_전원_노출한다() {
		var repository = mock(BrandReadRepository.class);
		var directPostRepository = mock(BrandDirectPostRepository.class);
		var hashtagTagRepository = mock(BrandHashtagTagRepository.class);
		given(repository.findHashtagPosts(eq(1L), any(), anyInt())).willReturn(List.of(
				hashtagRowWithAuthor("HHH", "poster1"), hashtagRowWithAuthor("III", "poster2")));
		given(repository.findBrandPoolStatus(eq(1L), any())).willReturn(List.of());
		// hashtagTagRepository.findByUserAndBrand 미스텁 — Mockito 기본값 empty(시딩 전).

		var assembler = new BrandHashtagPostAssembler(repository, directPostRepository, hashtagTagRepository);
		List<BrandHashtagPostResponse> result = assembler.assembleForBrand(USER_ID, 1L);

		assertThat(result).extracting(BrandHashtagPostResponse::shortcode).containsExactlyInAnyOrder("HHH", "III");
		// 원장이 비어 있다는 걸 이미 아는 순간 매칭 조회는 불필요하다(원장 조회 1번으로 판정 종료).
		verify(repository, never()).findMatchedTags(anyLong(), any());
	}

	// ---------- count 전용 경로(P2, 2026-08-27) ----------

	/**
	 * count는 목록과 <b>같은 판정</b>을 슬림 조회 위에서 태운 값이다 — 모든 count 테스트가 같은 시드에서
	 * {@code countForBrand == assembleForBrand().size()}를 단언한다(판정을 복제하면 두 표면의 숫자가
	 * 조용히 갈라진다). 시드는 두 조회에 한 번에 깐다 — 슬림 조회는 목록 조회와 술어가 동형이라
	 * (리포지토리 테스트가 봉인) 같은 행 집합을 돌려주는 게 실제 계약이다.
	 */
	private static void givenHashtagSeed(BrandReadRepository repository,
			BrandReadRepository.BrandHashtagPostRow... rows) {
		given(repository.findHashtagPosts(eq(1L), any(), anyInt())).willReturn(List.of(rows));
		given(repository.findHashtagPostCodes(eq(1L), any(), anyInt())).willReturn(
				Stream.of(rows).map(BrandReadRepository.BrandHashtagPostRow::shortCode).toList());
	}

	@Test
	void count는_tagged_겹침_행을_제외하고_목록_크기와_같다() {
		var repository = mock(BrandReadRepository.class);
		var directPostRepository = mock(BrandDirectPostRepository.class);
		givenHashtagSeed(repository, hashtagRowWithAuthor("HHH", "poster1"),
				hashtagRowWithAuthor("III", "poster2"));
		given(repository.findBrandPoolStatus(eq(1L), any())).willReturn(List.of(
				poolStatus("HHH", true, false),    // tagged-only — 제외
				poolStatus("III", true, true)));   // direct 살아 있음 — 유지

		var assembler = new BrandHashtagPostAssembler(repository, directPostRepository,
				mock(BrandHashtagTagRepository.class));

		assertThat(assembler.countForBrand(USER_ID, 1L)).isEqualTo(1);
		assertThat(assembler.countForBrand(USER_ID, 1L)).isEqualTo(assembler.assembleForBrand(USER_ID, 1L).size());
	}

	@Test
	void count는_내_태그_교집합만_센다() {
		var repository = mock(BrandReadRepository.class);
		var directPostRepository = mock(BrandDirectPostRepository.class);
		var hashtagTagRepository = mock(BrandHashtagTagRepository.class);
		givenHashtagSeed(repository, hashtagRowWithAuthor("HHH", "poster1"),
				hashtagRowWithAuthor("III", "poster2"));
		given(repository.findBrandPoolStatus(eq(1L), any())).willReturn(List.of());
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, 1L)).willReturn(Set.of("cclime"));
		given(repository.findMatchedTags(eq(1L), any())).willReturn(List.of(
				new BrandReadRepository.MatchedTagRow("HHH", "cclime"),
				new BrandReadRepository.MatchedTagRow("III", "다른브랜드")));

		var assembler = new BrandHashtagPostAssembler(repository, directPostRepository, hashtagTagRepository);

		assertThat(assembler.countForBrand(USER_ID, 1L)).isEqualTo(1);
		assertThat(assembler.countForBrand(USER_ID, 1L)).isEqualTo(assembler.assembleForBrand(USER_ID, 1L).size());
	}

	/** 매칭 기록이 없는 행은 목록과 같은 fail-open이다 — count에서만 숨으면 뱃지와 목록이 어긋난다. */
	@Test
	void count는_매칭_기록이_없으면_fail_open으로_센다() {
		var repository = mock(BrandReadRepository.class);
		var directPostRepository = mock(BrandDirectPostRepository.class);
		var hashtagTagRepository = mock(BrandHashtagTagRepository.class);
		givenHashtagSeed(repository, hashtagRowWithAuthor("HHH", "poster1"));
		given(repository.findBrandPoolStatus(eq(1L), any())).willReturn(List.of());
		given(hashtagTagRepository.findByUserAndBrand(USER_ID, 1L)).willReturn(Set.of("cclime"));
		given(repository.findMatchedTags(eq(1L), any())).willReturn(List.of());   // 매칭 기록 없음

		var assembler = new BrandHashtagPostAssembler(repository, directPostRepository, hashtagTagRepository);

		assertThat(assembler.countForBrand(USER_ID, 1L)).isEqualTo(1);
		assertThat(assembler.countForBrand(USER_ID, 1L)).isEqualTo(assembler.assembleForBrand(USER_ID, 1L).size());
	}

	/** 원장 미시딩(내 태그 0개)이면 목록과 같이 필터를 건너뛰고 전원을 센다. */
	@Test
	void count는_원장_미시딩이면_전원을_센다() {
		var repository = mock(BrandReadRepository.class);
		var directPostRepository = mock(BrandDirectPostRepository.class);
		var hashtagTagRepository = mock(BrandHashtagTagRepository.class);
		givenHashtagSeed(repository, hashtagRowWithAuthor("HHH", "poster1"),
				hashtagRowWithAuthor("III", "poster2"));
		given(repository.findBrandPoolStatus(eq(1L), any())).willReturn(List.of());
		// findByUserAndBrand 미스텁 — Mockito 기본값 empty(시딩 전).

		var assembler = new BrandHashtagPostAssembler(repository, directPostRepository, hashtagTagRepository);

		assertThat(assembler.countForBrand(USER_ID, 1L)).isEqualTo(2);
		assertThat(assembler.countForBrand(USER_ID, 1L)).isEqualTo(assembler.assembleForBrand(USER_ID, 1L).size());
		verify(repository, never()).findMatchedTags(anyLong(), any());
	}

	/** 발견분이 하나도 없으면 후속 조회 없이 0 — 빈 IN 절·불필요한 왕복 방지(목록과 같은 관용구). */
	@Test
	void 발견_게시물이_없으면_count는_0이고_후속_조회를_생략한다() {
		var repository = mock(BrandReadRepository.class);
		var directPostRepository = mock(BrandDirectPostRepository.class);
		var assembler = new BrandHashtagPostAssembler(repository, directPostRepository,
				mock(BrandHashtagTagRepository.class));

		assertThat(assembler.countForBrand(USER_ID, 1L)).isZero();
		verify(repository, never()).findBrandPoolStatus(anyLong(), any());
	}

	/** count는 배지(brandPostId) 파생이 없다 — 등록자 원장은 셀 때 쓸 데가 없으므로 조회하지 않는다. */
	@Test
	void count는_등록자_원장을_조회하지_않는다() {
		var repository = mock(BrandReadRepository.class);
		var directPostRepository = mock(BrandDirectPostRepository.class);
		givenHashtagSeed(repository, hashtagRowWithAuthor("HHH", "poster1"));
		given(repository.findBrandPoolStatus(eq(1L), any())).willReturn(List.of(poolStatus("HHH", false, true)));

		var assembler = new BrandHashtagPostAssembler(repository, directPostRepository,
				mock(BrandHashtagTagRepository.class));

		assertThat(assembler.countForBrand(USER_ID, 1L)).isEqualTo(1);
		verify(directPostRepository, never()).shortCodesByUser(anyLong());
	}

	private static BrandReadRepository.BrandPoolStatusRow poolStatus(String shortCode, boolean tagDetected,
			boolean directRegistered) {
		return new BrandReadRepository.BrandPoolStatusRow(shortCode, tagDetected, directRegistered,
				OffsetDateTime.parse("2026-08-01T00:00:00Z"));
	}

	private static BrandReadRepository.BrandHashtagPostRow hashtagRowWithAuthor(String code, String authorUsername) {
		return new BrandReadRepository.BrandHashtagPostRow(code, "#브랜드명", authorUsername,
				"해시태그 인플루언서", "https://cdn/hashtag-author.jpg",
				OffsetDateTime.parse("2026-08-06T01:00:00Z"), "캡션", "REELS",
				"https://cdn/hashtag-thumb.jpg", 20L, 3L, OffsetDateTime.parse("2026-08-06T02:00:00Z"), null, null);
	}

	// ---------- 픽스처 ----------

	private static BrandReadRepository.BrandHashtagPostRow hashtagRow(String code, String contentType) {
		return hashtagRow(code, contentType, "해시태그 캡션");
	}

	private static BrandReadRepository.BrandHashtagPostRow hashtagRow(String code, String contentType,
			String caption) {
		return new BrandReadRepository.BrandHashtagPostRow(code, "#브랜드명", "hashtag_influencer",
				"해시태그 인플루언서", "https://cdn/hashtag-author.jpg",
				OffsetDateTime.parse("2026-08-06T01:00:00Z"), caption, contentType,
				"https://cdn/hashtag-thumb.jpg", 20L, 3L, OffsetDateTime.parse("2026-08-06T02:00:00Z"),
				null, null);
	}
}

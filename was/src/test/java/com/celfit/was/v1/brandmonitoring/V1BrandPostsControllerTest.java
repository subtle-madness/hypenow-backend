package com.celfit.was.v1.brandmonitoring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.celfit.was.auth.AppUser;
import com.celfit.was.auth.AppUserDetails;
import com.celfit.was.config.SecurityConfig;
import com.celfit.was.monitoring.BrandDirectPostRepository;
import com.celfit.was.monitoring.BrandHashtagTagRepository;
import com.celfit.was.monitoring.BrandLinkRepository;
import com.celfit.was.monitoring.BrandLinkRow;
import com.celfit.was.monitoring.BrandPostCampaignRepository;
import com.celfit.was.monitoring.BrandReadRepository;
import com.celfit.was.monitoring.BrandReadRepository.AuthorRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandAccountRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandCommentRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandHashtagPostRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandPostMetaRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandSnapshotRow;
import com.celfit.was.monitoring.BrandReadRepository.BrandTaggedPostRow;
import com.celfit.was.monitoring.MonitoringItemRepository;
import com.celfit.was.v1.common.V1ApiException;
import com.celfit.was.v1.common.V1ExceptionAdvice;
import com.celfit.was.v1.monitoring.TrackingItemAssembler;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * /v1/brand-monitoring 게시물 표면 계약(스펙 §6-1·§6-2) — counts 정합, 필터·정렬, 소유권 403,
 * 미소유 게시물 404를 고정한다. 어셈블러는 실 빈으로 붙이고 DB 접점(BrandReadRepository·
 * BrandPostCampaignRepository)만 mock한다 — 조립·병합·필터 전 구간이 실제로 돈다.
 *
 * <p>2026-08-18 direct 통합(T10) 이후 tagged·direct는 같은 {@code findBrandPostsInWindow} 조회의
 * 파생값이다 — {@code directPostRepository}·{@code trackingItemAssembler}는 과도기 폴백
 * (이관 전 매핑, {@code migrated_at IS NULL})에만 쓰이고 이 표면 계약 테스트는 기본적으로 건드리지
 * 않는다(Mockito 기본값이 빈 목록이라 폴백은 자연히 no-op).
 */
@WebMvcTest(controllers = V1BrandPostsController.class,
		properties = {"was.cors.allowed-origins=http://localhost:3000", "monitoring.enabled=true"})
@Import({BrandPostAssembler.class, BrandHashtagPostAssembler.class, V1ExceptionAdvice.class, SecurityConfig.class})
class V1BrandPostsControllerTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	BrandLinkRepository linkRepository;
	@MockitoBean
	BrandReadRepository brandReadRepository;
	@MockitoBean
	BrandPostCampaignRepository postCampaignRepository;
	/** 과도기 폴백 전용(T10) — 이 표면 계약 테스트는 건드리지 않는다(기본값이 빈 목록). */
	@MockitoBean
	BrandDirectPostRepository directPostRepository;
	/**
	 * 해시태그 발견 목록 내 태그 필터(08-19) 전용 — 이 표면 계약 테스트는 건드리지 않는다(기본값이
	 * 빈 Set이라 findByUserAndBrand가 항상 empty → 필터 자체가 스킵되고 기존 동작 그대로 통과한다).
	 */
	@MockitoBean
	BrandHashtagTagRepository hashtagTagRepository;
	/** 과도기 폴백 전용(T10). */
	@MockitoBean
	TrackingItemAssembler trackingItemAssembler;
	@MockitoBean
	MonitoringItemRepository monitoringItemRepository;
	/** 직접 등록(§6-4)의 판정 로직은 V1BrandDirectPostServiceTest가 본다 — 여기는 표면 계약만. */
	@MockitoBean
	V1BrandDirectPostService directPostService;
	@MockitoBean
	Clock clock;

	private static final String POST_URL = "https://www.instagram.com/reel/DEF/";

	private static AppUserDetails principal() {
		return new AppUserDetails(new AppUser(7L, "user@example.com", "hash", "USER",
				OffsetDateTime.parse("2026-06-01T00:00:00Z")));
	}

	@BeforeEach
	void ownedBrand() {
		// 링크 창 컷의 기준 시각 고정 — 고정하지 않으면 2026-08-xx 고정 날짜 데이터가 시간이 지나며
		// 창 밖으로 밀려 테스트 전체가 시한부가 된다. KST 2026-08-08 21:00.
		given(clock.instant()).willReturn(Instant.parse("2026-08-08T12:00:00Z"));
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link()));
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(link()));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(account()));
		// 인덱스 패스 경량 프로젝션(2026-08-27 목록 타임아웃 해소)은 기존 시드 관용구(givenTagged의
		// findBrandPostsInWindow + findPostMeta·findSnapshots 고정)에서 파생시킨다 — 시드를 그대로
		// 재사용하고, 두 산지의 값이 어긋나 counts·정렬이 풀 조립과 불일치하는 시드 실수도 원천 차단한다.
		given(brandReadRepository.findBrandPostIndex(anyLong(), any(), anyBoolean())).willAnswer(inv -> {
			var metaByCode = new java.util.LinkedHashMap<String, BrandPostMetaRow>();
			for (BrandPostMetaRow m : brandReadRepository.findPostMeta(List.of())) {
				metaByCode.putIfAbsent(m.shortCode(), m);
			}
			return brandReadRepository.findBrandPostsInWindow(0L, null, true).stream()
					.map(r -> {
						BrandPostMetaRow m = metaByCode.get(r.shortCode());
						return new BrandReadRepository.BrandPostIndexRow(r.shortCode(), r.takenAt(),
								r.tagDetectedAt(), r.directRegisteredAt(),
								m == null ? null : m.isPaidPartnership(), m == null ? null : m.caption(),
								r.unavailableAt(), r.authorUsername(), r.authorIgUserId());
					})
					.toList();
		});
		given(brandReadRepository.findBrandPostsByShortCodes(anyLong(), any())).willAnswer(inv -> {
			java.util.Collection<String> codes = inv.getArgument(1);
			return brandReadRepository.findBrandPostsInWindow(0L, null, true).stream()
					.filter(r -> codes.contains(r.shortCode()))
					.toList();
		});
		given(brandReadRepository.findLatestSnapshotsForBrand(anyLong(), any(), anyBoolean())).willAnswer(inv -> {
			var latest = new java.util.LinkedHashMap<String, BrandSnapshotRow>();
			for (BrandSnapshotRow row : brandReadRepository.findSnapshots(List.of())) {
				latest.merge(row.shortCode(), row, (a, b) -> b.capturedOn().isAfter(a.capturedOn()) ? b : a);
			}
			return latest.values().stream()
					.map(r -> new BrandReadRepository.LatestSnapshotRow(r.shortCode(), r.capturedOn(),
							r.contentType(), r.views(), r.likes(), r.likesHidden(), r.comments()))
					.toList();
		});
	}

	// ---------- 목록 ----------

	@Test
	void 목록은_12개월치_태그_게시물_전량을_자르지_않는다() throws Exception {
		// 정책 v1(08-09)로 수집·저장소 상한이 폐지됐는데 컨트롤러 POST_LIMIT(구 200)만 남아
		// 12개월치가 많은 브랜드(실측 463건)의 오래된 게시물이 소리 없이 잘렸다 — 250건 전량 서빙을 고정.
		var tagged = new BrandTaggedPostRow[250];
		var metas = new java.util.ArrayList<BrandPostMetaRow>(250);
		for (int i = 0; i < 250; i++) {
			String code = "P%03d".formatted(i);
			tagged[i] = taggedRow(code, OffsetDateTime.parse("2026-08-01T00:00:00Z").minusDays(i).toString());
			metas.add(meta(code, "REELS", null));
		}
		givenTagged(tagged);
		given(brandReadRepository.findPostMeta(any())).willReturn(metas);

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(250))
				.andExpect(jsonPath("$.meta.total").value(250));
	}

	@Test
	void 확장_수집_중에도_게시물_목록은_정상_서빙된다() throws Exception {
		// 확장 중 = last_swept_on·완주 시각 둘 다 null + 스윕 완주 사실(last_swept_at)만 있음
		// (08-13 개정 — expandWindow가 완주 시각도 리셋한다). FE는 확장 중에도 기존 데이터 위에
		// 진행 배너만 띄운다(요청서 §4 조건 ①) — 목록이 비면 그 UX가 무너진다.
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.of(
				new BrandAccountRow(100L, "lizda_official", null,
						OffsetDateTime.parse("2026-08-07T18:00:00Z"), OffsetDateTime.parse("2026-08-01T00:00:00Z"),
						null, null, 30876L, 12L, 340L, null, "리즈다",
						"https://cdn/pic.jpg", true, null, "ACTIVE", null,
						12, OffsetDateTime.parse("2026-08-12T10:00:00Z"), false, null)));
		givenTagged(taggedRow("P001", "2026-08-01T00:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("P001", "REELS", null)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1));
	}

	@Test
	void 목록은_tagged와_direct를_합치고_counts는_필터_전_전량이다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"), taggedRow("BBB", "2026-08-05T01:00:00Z"),
				directRow("XYZ", "2026-08-06T01:00:00Z"));
		givenOwnedByPrincipal("XYZ");   // XYZ는 principal(7L)이 직접 등록한 게시물 — 노출 대상.
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(
				meta("AAA", "REELS", true), meta("BBB", "FEED", false), meta("XYZ", "REELS", null)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(3))
				// 기본 정렬은 업로드 최신순 — direct(8/6 업로드)와 tagged AAA(8/6)는 shortcode 타이브레이크.
				.andExpect(jsonPath("$.data[*].shortcode").value(Matchers.contains("AAA", "XYZ", "BBB")))
				.andExpect(jsonPath("$.meta.total").value(3))
				.andExpect(jsonPath("$.meta.limit").value(2000))
				.andExpect(jsonPath("$.meta.counts.all").value(3))
				.andExpect(jsonPath("$.meta.counts.tagged").value(2))
				.andExpect(jsonPath("$.meta.counts.direct").value(1))
				.andExpect(jsonPath("$.meta.counts.sponsored").value(1))
				.andExpect(jsonPath("$.meta.counts.organic").value(1))
				.andExpect(jsonPath("$.meta.counts.unknown").value(1))
				.andExpect(jsonPath("$.meta.lastCollectedAt").value("2026-08-08T03:00:00+09:00"));
	}

	// ---------- 등록자 전용 노출(요구사항, 08-19) ----------

	/**
	 * direct-only(해시태그 미감지) 게시물은 등록한 유저에게만 보인다 — 같은 브랜드를 보는 다른 유저
	 * 화면에 노출되던 버그의 회귀 방지. 원장(app.brand_direct_posts) 조회 기본값(빈 집합)이 곧
	 * "principal은 이 게시물의 등록자가 아니다"를 뜻한다(givenOwnedByPrincipal 호출 없음).
	 */
	@Test
	void 다른_유저가_등록한_direct_전용_게시물은_보이지_않는다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"), directRow("XYZ", "2026-08-06T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("AAA", "REELS", null)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].shortcode").value("AAA"))
				.andExpect(jsonPath("$.meta.total").value(1))
				.andExpect(jsonPath("$.meta.counts.all").value(1))
				.andExpect(jsonPath("$.meta.counts.direct").value(0));
	}

	/** direct-only 게시물은 등록한 본인에게는 정상적으로 보이고 source도 "direct"다. */
	@Test
	void 등록자에게는_직접_등록한_게시물이_보인다() throws Exception {
		givenTagged(directRow("XYZ", "2026-08-06T01:00:00Z"));
		givenOwnedByPrincipal("XYZ");
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("XYZ", "REELS", null)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].shortcode").value("XYZ"))
				.andExpect(jsonPath("$.data[0].source").value("direct"));
	}

	/**
	 * 해시태그로 감지된 게시물(tag_detected_at IS NOT NULL)은 같은 게시물을 다른 유저가 직접
	 * 등록했더라도(겹침 행) 항상 모두에게 보인다 — 노출 필터는 direct-only에만 적용된다. 다만 source
	 * 표시는 조회자 관점으로 갈린다(등록자가 아니므로 "tagged").
	 */
	@Test
	void 해시태그로_감지된_게시물은_다른_유저의_direct_등록과_겹쳐도_보인다() throws Exception {
		givenTagged(overlapRow("GHI", "2026-08-06T01:00:00Z"));
		// givenOwnedByPrincipal 호출 없음 — principal은 이 게시물의 등록자가 아니다.
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("GHI", "REELS", null)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].shortcode").value("GHI"))
				.andExpect(jsonPath("$.data[0].source").value("tagged"));
	}

	/**
	 * 목록 표면 계약(2026-08-27 FE 요청 1) — 목록 그리드는 댓글을 렌더하지 않으므로
	 * {@code recentComments}를 싣지 않는다(빈 배열·키 유지, 계약 무결성 규칙 #1). 댓글 윈도우 쿼리
	 * 자체가 돌면 안 된다(08-12 실측 조립 시간의 57%). 스냅샷 유래 지표(commentsTotal)는 유지.
	 * 댓글은 상세 조회 전용이다({@code 상세는_댓글을_포함한다}).
	 */
	@Test
	void 목록은_지표만_내려주고_댓글은_비운다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("AAA", "REELS", true)));
		given(brandReadRepository.findSnapshots(any())).willReturn(List.of(
				snapshotRow("AAA", 5, 100L), snapshotRow("AAA", 6, 300L)));
		given(brandReadRepository.findComments(any(), anyInt())).willReturn(List.of(
				new BrandCommentRow("AAA", "c1", "glowdeep_92", "좋아요", 3L,
						OffsetDateTime.parse("2026-08-06T05:00:00Z"), null)));
		given(brandReadRepository.findAuthors(any())).willReturn(List.of(
				new AuthorRow("9001", "glowdeep_92", "글로우딥", 12345L, "https://cdn/author.jpg", true, null)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].postUrl").value("https://www.instagram.com/reel/AAA/"))
				.andExpect(jsonPath("$.data[0].sponsorship").value("sponsored"))
				.andExpect(jsonPath("$.data[0].trackingStatus").value("tracking"))
				.andExpect(jsonPath("$.data[0].authorFullName").value("글로우딥"))
				.andExpect(jsonPath("$.data[0].authorFollowers").value(12345))
				.andExpect(jsonPath("$.data[0].snapshots.length()").value(2))
				.andExpect(jsonPath("$.data[0].latestSnapshot.views").value(300))
				.andExpect(jsonPath("$.data[0].commentsTotal").value(12))
				.andExpect(jsonPath("$.data[0].commentsCollectedCount").value(0))
				.andExpect(jsonPath("$.data[0]", Matchers.hasKey("recentComments")))
				.andExpect(jsonPath("$.data[0].recentComments.length()").value(0))
				// nullable 키는 생략하지 않고 명시적 null(계약 무결성 규칙 #1).
				.andExpect(jsonPath("$.data[0]", Matchers.hasKey("trackingEndedAt")))
				.andExpect(jsonPath("$.data[0].trackingEndedAt").value(Matchers.nullValue()));

		then(brandReadRepository).should(never()).findComments(any(), anyInt());
	}

	// ---------- 페이지네이션(2026-08-27 FE 요청 2·3) ----------

	@Test
	void limit_offset은_정렬된_전량의_슬라이스다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"), taggedRow("BBB", "2026-08-05T01:00:00Z"),
				taggedRow("CCC", "2026-08-04T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(
				meta("AAA", "REELS", null), meta("BBB", "REELS", null), meta("CCC", "REELS", null)));

		// 두 페이지 합집합 = 전량, 중복·누락 없음 — 정렬(업로드 최신순 + shortcode)이 페이지 간 일관.
		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?limit=2").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].shortcode").value(Matchers.contains("AAA", "BBB")));
		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?limit=2&offset=2")
						.with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].shortcode").value(Matchers.contains("CCC")));
	}

	@Test
	void 페이지네이션에도_total_counts는_전체_기준이다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"), taggedRow("BBB", "2026-08-05T01:00:00Z"),
				taggedRow("CCC", "2026-08-04T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(
				meta("AAA", "REELS", true), meta("BBB", "REELS", false), meta("CCC", "REELS", null)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?limit=1").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				// total·counts는 전량 계산 값 — 페이지로 잘리면 FE 필터 칩 숫자가 무너진다.
				.andExpect(jsonPath("$.meta.total").value(3))
				.andExpect(jsonPath("$.meta.counts.all").value(3))
				.andExpect(jsonPath("$.meta.counts.sponsored").value(1))
				.andExpect(jsonPath("$.meta.counts.organic").value(1))
				.andExpect(jsonPath("$.meta.counts.unknown").value(1))
				// meta.limit은 수집 상한 의미 그대로 — 페이지 정보는 meta.page로 분리(FE 협의 반영).
				.andExpect(jsonPath("$.meta.limit").value(2000))
				.andExpect(jsonPath("$.meta.page.offset").value(0))
				.andExpect(jsonPath("$.meta.page.limit").value(1));
	}

	@Test
	void 페이지네이션은_필터_적용_후에_잘린다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"), taggedRow("BBB", "2026-08-05T01:00:00Z"),
				taggedRow("CCC", "2026-08-04T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(
				meta("AAA", "REELS", true), meta("BBB", "REELS", false), meta("CCC", "REELS", true)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?sponsorship=sponsored&limit=1&offset=1")
						.with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].shortcode").value(Matchers.contains("CCC")))
				.andExpect(jsonPath("$.meta.total").value(2))    // 필터 후 전체(sponsored 2건)
				.andExpect(jsonPath("$.meta.counts.all").value(3));
	}

	@Test
	void 파라미터_생략_시_meta_page는_전량_표식이다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"), taggedRow("BBB", "2026-08-05T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(
				meta("AAA", "REELS", null), meta("BBB", "REELS", null)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(2))
				.andExpect(jsonPath("$.meta.page.offset").value(0))
				.andExpect(jsonPath("$.meta.page", Matchers.hasKey("limit")))
				.andExpect(jsonPath("$.meta.page.limit").value(Matchers.nullValue()));
	}

	@Test
	void offset만_주면_limit은_기본_100이다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"), taggedRow("BBB", "2026-08-05T01:00:00Z"),
				taggedRow("CCC", "2026-08-04T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(
				meta("AAA", "REELS", null), meta("BBB", "REELS", null), meta("CCC", "REELS", null)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?offset=1").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].shortcode").value(Matchers.contains("BBB", "CCC")))
				.andExpect(jsonPath("$.meta.page.offset").value(1))
				.andExpect(jsonPath("$.meta.page.limit").value(100));
	}

	@Test
	void limit_offset_범위_밖은_400이다() throws Exception {
		for (String query : List.of("limit=0", "limit=101", "offset=-1")) {
			mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?" + query).with(user(principal())))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
		}
	}

	@Test
	void performance_정렬도_페이지_간_일관이다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"), taggedRow("BBB", "2026-08-05T01:00:00Z"),
				taggedRow("CCC", "2026-08-04T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(
				meta("AAA", "REELS", null), meta("BBB", "REELS", null), meta("CCC", "REELS", null)));
		given(brandReadRepository.findSnapshots(any())).willReturn(List.of(
				snapshotRow("AAA", 6, 100L), snapshotRow("BBB", 6, 900L)));   // CCC는 스냅샷 없음 → null 마지막

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?sort=performance_desc&limit=2")
						.with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].shortcode").value(Matchers.contains("BBB", "AAA")));
		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?sort=performance_desc&limit=2&offset=2")
						.with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].shortcode").value(Matchers.contains("CCC")));
	}

	@Test
	void 협찬_필터는_data만_줄이고_counts는_그대로다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"), taggedRow("BBB", "2026-08-05T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(
				meta("AAA", "REELS", true), meta("BBB", "FEED", false)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?sponsorship=sponsored")
						.with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].shortcode").value("AAA"))
				.andExpect(jsonPath("$.meta.total").value(1))
				.andExpect(jsonPath("$.meta.counts.all").value(2));
	}

	@Test
	void source_필터는_direct만_남긴다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"), directRow("XYZ", "2026-08-06T01:00:00Z"));
		givenOwnedByPrincipal("XYZ");   // XYZ는 principal(7L)이 직접 등록한 게시물 — 노출 대상.
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(
				meta("AAA", "REELS", null), meta("XYZ", "REELS", null)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?source=direct").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].source").value("direct"))
				.andExpect(jsonPath("$.data[0].shortcode").value("XYZ"));
	}

	/**
	 * 회귀 케이스(2026-08-12 별도 탭 결정) — 해시태그 발견분은 더 이상 §6-1 목록에 섞이지 않는다.
	 * repository가 tagged·hashtag 둘 다 갖고 있어도 posts 응답은 tagged만 보이고, source 화이트리스트·
	 * meta.counts에서도 hashtag 어휘가 완전히 빠져야 한다(예전엔 여기 있었다 — Task 11 되돌림).
	 */
	@Test
	void 게시물_목록_API는_해시태그_발견분을_더이상_병합하지_않는다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("AAA", "REELS", null)));
		givenHashtag(hashtagRow("HHH", "2026-08-05T01:00:00Z"));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].shortcode").value("AAA"))
				.andExpect(jsonPath("$.meta.counts", Matchers.not(Matchers.hasKey("hashtag"))));

		then(brandReadRepository).should(never()).findHashtagPosts(anyLong(), any(), anyInt());
	}

	@Test
	void source_필터에_hashtag를_주면_400이다() throws Exception {
		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?source=hashtag").with(user(principal())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 업로드_기간_필터는_KST_날짜로_자른다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"), taggedRow("BBB", "2026-08-01T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(
				meta("AAA", "REELS", null), meta("BBB", "FEED", null)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?uploadedFrom=2026-08-05")
						.with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].shortcode").value("AAA"));
	}

	@Test
	void performance_desc는_최신_스냅샷_views_내림차순이고_null이_마지막이다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"), taggedRow("BBB", "2026-08-05T01:00:00Z"),
				taggedRow("CCC", "2026-08-04T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(
				meta("AAA", "REELS", null), meta("BBB", "REELS", null), meta("CCC", "REELS", null)));
		given(brandReadRepository.findSnapshots(any())).willReturn(List.of(
				snapshotRow("AAA", 6, 100L), snapshotRow("BBB", 6, 900L)));   // CCC는 스냅샷 없음

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?sort=performance_desc")
						.with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].shortcode").value(Matchers.contains("BBB", "AAA", "CCC")));
	}

	@Test
	void 알_수_없는_정렬키는_400이다() throws Exception {
		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?sort=likes_desc").with(user(principal())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 잘못된_날짜_형식은_400이다() throws Exception {
		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts?uploadedTo=2026-13-99")
						.with(user(principal())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 남의_계정_목록은_403이고_조회하지_않는다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 999L)).willReturn(Optional.empty());

		mockMvc.perform(get("/v1/brand-monitoring/accounts/999/posts").with(user(principal())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		then(brandReadRepository).should(never()).findBrandPostsInWindow(anyLong(), any(), anyBoolean());
	}

	@Test
	void 인증이_없으면_401이다() throws Exception {
		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts"))
				.andExpect(status().isUnauthorized());
	}

	// ---------- 링크 표시 창(2026-08-17 스펙) ----------

	@Test
	void 링크_창_밖_tagged는_목록과_counts에서_빠진다() throws Exception {
		// 자산은 12개월치를 들고 있어도(BBB: 4개월 전) 3개월 신청 유저에겐 창 안(AAA)만 보인다.
		given(linkRepository.findActiveByUserAndBrand(7L, 100L))
				.willReturn(Optional.of(linkWithMonths(3)));
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"), taggedRow("BBB", "2026-04-01T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(
				meta("AAA", "REELS", null), meta("BBB", "FEED", null)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].shortcode").value("AAA"))
				// counts도 자른 전량 기준 — 탭 뱃지가 유저 창과 일치해야 한다.
				.andExpect(jsonPath("$.meta.counts.all").value(1))
				.andExpect(jsonPath("$.meta.counts.tagged").value(1));
	}

	@Test
	void 링크_창_경계일은_포함이다() throws Exception {
		// 컷 = 2026-08-08(KST 고정) − 3개월 = 2026-05-08. 그 날짜 업로드(KST 10시)는 포함.
		given(linkRepository.findActiveByUserAndBrand(7L, 100L))
				.willReturn(Optional.of(linkWithMonths(3)));
		givenTagged(taggedRow("EDG", "2026-05-08T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("EDG", "REELS", null)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1));
	}

	@Test
	void direct는_링크_창_밖이어도_포함이다() throws Exception {
		// 직접 등록은 유저가 URL을 명시한 추적 대상 — 창은 태그 수집 범위의 개념이라 적용하지 않는다.
		// direct 업로드일(2026-02-01)은 1개월 창(컷 2026-07-08) 한참 밖 — 예외 규칙이 실제로 판정을
		// 우회하는지 검증한다(창 안 날짜면 예외 없이도 통과해 테스트가 아무것도 못 잡는다).
		given(linkRepository.findActiveByUserAndBrand(7L, 100L))
				.willReturn(Optional.of(linkWithMonths(1)));
		givenTagged(taggedRow("AAA", "2026-04-01T01:00:00Z"),   // 창 밖 tagged — 제외 대조군
				directRow("XYZ", "2026-02-01T01:00:00Z"));
		givenOwnedByPrincipal("XYZ");   // XYZ는 principal(7L)이 직접 등록한 게시물 — 노출 대상.
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(
				meta("AAA", "REELS", null), meta("XYZ", "REELS", null)));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].source").value("direct"));
	}

	@Test
	void 링크_창_밖_게시물_상세는_404다() throws Exception {
		// 목록에 없는 게시물이 상세로는 열리는 불일치 방지 — 상세도 같은 창이다.
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of(linkWithMonths(3)));
		givenTagged(taggedRow("OLD", "2026-04-01T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("OLD", "REELS", null)));

		mockMvc.perform(get("/v1/brand-monitoring/posts/OLD").with(user(principal())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	// ---------- 해시태그 발견 게시물 전용 API(스펙 §8, 별도 탭 결정 2026-08-12) ----------

	@Test
	void 해시태그_발견_게시물_목록은_열거_필드를_그대로_내려준다() throws Exception {
		givenHashtag(hashtagRow("HHH", "2026-08-06T01:00:00Z"));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/hashtag-posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].shortcode").value("HHH"))
				.andExpect(jsonPath("$.data[0].postUrl").value("https://www.instagram.com/p/HHH/"))
				.andExpect(jsonPath("$.data[0].matchedTag").value("#브랜드명"))
				.andExpect(jsonPath("$.data[0].takenAt").value("2026-08-06T10:00:00+09:00"))
				.andExpect(jsonPath("$.data[0].caption").value("해시태그 캡션"))
				.andExpect(jsonPath("$.data[0].contentType").value("reels"))
				.andExpect(jsonPath("$.data[0].thumbnailUrl").value("https://cdn/hashtag-thumb.jpg"))
				.andExpect(jsonPath("$.data[0].authorUsername").value("hashtag_influencer"))
				.andExpect(jsonPath("$.data[0].authorFullName").value("해시태그 인플루언서"))
				.andExpect(jsonPath("$.data[0].authorProfilePicUrl").value("https://cdn/hashtag-author.jpg"))
				.andExpect(jsonPath("$.data[0].authorProfileUrl")
						.value("https://www.instagram.com/hashtag_influencer/"))
				.andExpect(jsonPath("$.data[0].likes").value(20))
				.andExpect(jsonPath("$.data[0].comments").value(3))
				.andExpect(jsonPath("$.data[0].sponsorship").value("unknown"))
				.andExpect(jsonPath("$.data[0].firstSeenAt").value("2026-08-06T11:00:00+09:00"));
	}

	@Test
	void 해시태그_발견_게시물_협찬은_캡션_확정_키워드로만_판정한다() throws Exception {
		givenHashtag(hashtagRow("HHH", "2026-08-06T01:00:00Z", "오늘의 #협찬 후기"),
				hashtagRow("III", "2026-08-05T01:00:00Z", "그냥 일상"));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/hashtag-posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[?(@.shortcode=='HHH')].sponsorship").value(Matchers.contains("sponsored")))
				.andExpect(jsonPath("$.data[?(@.shortcode=='III')].sponsorship").value(Matchers.contains("unknown")));
	}

	/**
	 * 등록자 스코프 배지(요구사항, 08-19) — 남이 direct 등록(수집)한 발견 게시물은 내 목록에서
	 * brandPostId가 null(미수집)이어야 한다. 해시태그 감지 데이터(행 자체)는 여전히 보인다.
	 */
	@Test
	void 남이_수집한_발견_게시물은_내게_미수집으로_보인다() throws Exception {
		givenHashtag(hashtagRow("HHH", "2026-08-06T01:00:00Z"));
		given(brandReadRepository.findBrandPoolStatus(eq(100L), any())).willReturn(List.of(
				new BrandReadRepository.BrandPoolStatusRow("HHH", false, true,
						OffsetDateTime.parse("2026-08-06T01:00:00Z"))));
		// directPostRepository.shortCodesByUser(7L) 미스텁 — Mockito 기본값 empty(내가 등록 안 함).

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/hashtag-posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].shortcode").value("HHH"))
				.andExpect(jsonPath("$.data[0].brandPostId").value(org.hamcrest.Matchers.nullValue()));
	}

	/** 내가 직접 등록(수집)한 발견 게시물은 brandPostId가 채워진다. */
	@Test
	void 내가_수집한_발견_게시물은_brandPostId가_채워진다() throws Exception {
		givenHashtag(hashtagRow("HHH", "2026-08-06T01:00:00Z"));
		given(brandReadRepository.findBrandPoolStatus(eq(100L), any())).willReturn(List.of(
				new BrandReadRepository.BrandPoolStatusRow("HHH", false, true,
						OffsetDateTime.parse("2026-08-06T01:00:00Z"))));
		given(directPostRepository.shortCodesByUser(7L)).willReturn(Set.of("HHH"));

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/hashtag-posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].brandPostId").value("HHH"));
	}

	@Test
	void 해시태그_발견_게시물이_없으면_빈_배열이다() throws Exception {
		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/hashtag-posts").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").isArray())
				.andExpect(jsonPath("$.data.length()").value(0));
	}

	@Test
	void 남의_계정_해시태그_목록은_403이고_조회하지_않는다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 999L)).willReturn(Optional.empty());

		mockMvc.perform(get("/v1/brand-monitoring/accounts/999/hashtag-posts").with(user(principal())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

		then(brandReadRepository).should(never()).findHashtagPosts(anyLong(), any(), anyInt());
	}

	@Test
	void 없는_브랜드_계정의_해시태그_목록은_404다() throws Exception {
		given(linkRepository.findActiveByUserAndBrand(7L, 100L)).willReturn(Optional.of(link()));
		given(brandReadRepository.findAccount(100L)).willReturn(Optional.empty());

		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/hashtag-posts").with(user(principal())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void 해시태그_목록_인증이_없으면_401이다() throws Exception {
		mockMvc.perform(get("/v1/brand-monitoring/accounts/100/hashtag-posts"))
				.andExpect(status().isUnauthorized());
	}

	// ---------- 상세 ----------

	@Test
	void 상세는_shortcode로_같은_조립을_돌려준다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("AAA", "REELS", true)));

		mockMvc.perform(get("/v1/brand-monitoring/posts/AAA").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value("AAA"))
				.andExpect(jsonPath("$.data.source").value("tagged"))
				.andExpect(jsonPath("$.data.sponsorship").value("sponsored"));
	}

	/** 상세도 인덱스 경로다(2026-08-27) — 브랜드 전량이 아니라 요청된 shortcode 1건만 풀 조립한다. */
	@Test
	void 상세는_해당_게시물만_조립한다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"), taggedRow("BBB", "2026-08-05T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("BBB", "REELS", null)));

		mockMvc.perform(get("/v1/brand-monitoring/posts/BBB").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value("BBB"));

		then(brandReadRepository).should().findPostMeta(eq(Set.of("BBB")));
		then(brandReadRepository).should().findSnapshots(eq(Set.of("BBB")));
	}

	/** 목록이 댓글을 비우는 계약(FE 요청 1)의 반쪽 — 댓글은 상세에서만 내려간다. */
	@Test
	void 상세는_댓글을_포함한다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("AAA", "REELS", null)));
		given(brandReadRepository.findComments(any(), anyInt())).willReturn(List.of(
				new BrandCommentRow("AAA", "c1", "glowdeep_92", "좋아요", 3L,
						OffsetDateTime.parse("2026-08-06T05:00:00Z"), null)));

		mockMvc.perform(get("/v1/brand-monitoring/posts/AAA").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.commentsCollectedCount").value(1))
				.andExpect(jsonPath("$.data.recentComments[0].author").value("gl***92"));
	}

	@Test
	void 내_tagged와_direct_어디에도_없는_게시물은_404다() throws Exception {
		givenTagged(taggedRow("AAA", "2026-08-06T01:00:00Z"));
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("AAA", "REELS", null)));

		mockMvc.perform(get("/v1/brand-monitoring/posts/ZZZ").with(user(principal())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	/** 상세도 목록과 같은 조립을 타므로 등록자 전용 노출 규칙이 그대로 적용된다 — 남의 direct 전용 게시물은 404. */
	@Test
	void 다른_유저가_등록한_direct_전용_게시물_상세는_404다() throws Exception {
		givenTagged(directRow("XYZ", "2026-08-06T01:00:00Z"));
		// givenOwnedByPrincipal 호출 없음 — principal은 이 게시물의 등록자가 아니다.
		given(brandReadRepository.findPostMeta(any())).willReturn(List.of(meta("XYZ", "REELS", null)));

		mockMvc.perform(get("/v1/brand-monitoring/posts/XYZ").with(user(principal())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void 브랜드_연결이_없으면_상세는_404다() throws Exception {
		given(linkRepository.findAllActiveByUser(7L)).willReturn(List.of());

		mockMvc.perform(get("/v1/brand-monitoring/posts/AAA").with(user(principal())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	// ---------- 직접 등록(§6-4) ----------

	@Test
	void 직접_등록은_202로_접수하고_요청_본문을_그대로_서비스에_넘긴다() throws Exception {
		given(directPostService.register(7L, 100L, List.of(POST_URL), 30, "9"))
				.willReturn(new BrandDirectRegistrationResponse("55", "2026-08-08T10:00:00+09:00",
						List.of(new BrandDirectRegistrationResponse.Entry(POST_URL, "pending", null, null,
								"DEF", "301"))));

		mockMvc.perform(post("/v1/brand-monitoring/accounts/100/direct-posts").with(user(principal()))
						.with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"postUrls\":[\"" + POST_URL + "\"],\"trackingDays\":30,\"campaignId\":\"9\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.data.registrationId").value("55"))
				.andExpect(jsonPath("$.data.entries[0].result").value("pending"))
				.andExpect(jsonPath("$.data.entries[0].brandPostId").value("DEF"))
				.andExpect(jsonPath("$.data.entries[0].monitoringItemId").value("301"))
				// nullable 키는 생략하지 않고 명시적 null(계약 무결성 규칙 #1).
				.andExpect(jsonPath("$.data.entries[0]", Matchers.hasKey("reasonCode")));
	}

	@Test
	void 등록_상태_조회는_같은_셰이프를_돌려준다() throws Exception {
		given(directPostService.get(7L, "55"))
				.willReturn(new BrandDirectRegistrationResponse("55", "2026-08-08T10:00:00+09:00",
						List.of(new BrandDirectRegistrationResponse.Entry(POST_URL, "success", null, null,
								"DEF", "301"))));

		mockMvc.perform(get("/v1/brand-monitoring/direct-registrations/55").with(user(principal())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.entries[0].result").value("success"))
				.andExpect(jsonPath("$.data.requestedAt").value("2026-08-08T10:00:00+09:00"));
	}

	@Test
	void 없는_등록_상태는_404다() throws Exception {
		given(directPostService.get(7L, "55")).willThrow(V1ApiException.notFound("등록 요청을 찾을 수 없습니다."));

		mockMvc.perform(get("/v1/brand-monitoring/direct-registrations/55").with(user(principal())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	// ---------- 취소(2026-08-17 FE 요청) ----------

	@Test
	void 취소는_서비스에_위임하고_204를_돌려준다() throws Exception {
		mockMvc.perform(post("/v1/brand-monitoring/posts/DEF/cancel").with(user(principal())).with(csrf()))
				.andExpect(status().isNoContent());

		then(directPostService).should().cancel(7L, "DEF");
	}

	@Test
	void tagged_행_취소는_400_TAGGED_POST_NOT_CANCELABLE로_전달된다() throws Exception {
		willThrow(V1ApiException.badRequest("TAGGED_POST_NOT_CANCELABLE", "태그로 발견된 게시물은 취소할 수 없어요."))
				.given(directPostService).cancel(7L, "AAA");

		mockMvc.perform(post("/v1/brand-monitoring/posts/AAA/cancel").with(user(principal())).with(csrf()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("TAGGED_POST_NOT_CANCELABLE"));
	}

	@Test
	void 대상_없는_취소는_404다() throws Exception {
		willThrow(V1ApiException.notFound("대상을 찾을 수 없습니다.")).given(directPostService).cancel(7L, "ZZZ");

		mockMvc.perform(post("/v1/brand-monitoring/posts/ZZZ/cancel").with(user(principal())).with(csrf()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void 취소_인증이_없으면_401이다() throws Exception {
		// CSRF 없는 POST는 인증 필터보다 먼저 걸려 403이다 — 인증 부재를 순수하게 보려면 csrf()는 붙이고
		// 인증만 뺀다(다른 v1 POST 401 테스트와 같은 관용구, 예: V1BrandAccountsControllerTest에는
		// 해당 케이스가 없어 여기서 새로 정한다).
		mockMvc.perform(post("/v1/brand-monitoring/posts/DEF/cancel").with(csrf()))
				.andExpect(status().isUnauthorized());
	}

	// ---------- 스텁 ----------

	/**
	 * 목록·상세는 표시 표면이라 <b>정산분 조회</b>를 탄다(2026-08-13 완결 배치 서빙) — 여기가
	 * findBrandPostsInWindow(전량)로 되돌아가면 이 클래스 전체가 빈 목록으로 떨어져 바로 드러난다.
	 */
	private void givenTagged(BrandTaggedPostRow... rows) {
		given(brandReadRepository.findBrandPostsInWindow(anyLong(), any(), org.mockito.ArgumentMatchers.eq(true)))
				.willReturn(List.of(rows));
	}

	private void givenHashtag(BrandHashtagPostRow... rows) {
		given(brandReadRepository.findHashtagPosts(anyLong(), any(), anyInt())).willReturn(List.of(rows));
	}

	/**
	 * 등록자 전용 노출 요구사항(08-19) — direct-only 게시물은 등록자(app.brand_direct_posts 원장)만
	 * 볼 수 있다. 이 파일의 principal은 항상 userId=7L이라 그 관점으로 원장을 스텁한다.
	 */
	private void givenOwnedByPrincipal(String... shortCodes) {
		given(directPostRepository.shortCodesByUser(7L)).willReturn(Set.of(shortCodes));
	}

	private static BrandLinkRow link() {
		return new BrandLinkRow(1L, 7L, 100L, "lizda_official", BrandAccountType.OWN, 12,
				OffsetDateTime.parse("2026-08-01T00:00:00Z"), null);
	}

	private static BrandLinkRow linkWithMonths(int months) {
		return new BrandLinkRow(1L, 7L, 100L, "lizda_official", BrandAccountType.OWN, months,
				OffsetDateTime.parse("2026-08-01T00:00:00Z"), null);
	}

	private static BrandAccountRow account() {
		return new BrandAccountRow(100L, "lizda_official", LocalDate.of(2026, 8, 8),
				OffsetDateTime.parse("2026-08-07T18:00:00Z"), OffsetDateTime.parse("2026-08-01T00:00:00Z"),
				OffsetDateTime.parse("2026-08-01T01:00:00Z"), null, 30876L, 12L, 340L, null, "리즈다",
				"https://cdn/pic.jpg", true, null, "ACTIVE", null,
				12, OffsetDateTime.parse("2026-08-01T00:00:00Z"), false, null);
	}

	private static BrandTaggedPostRow taggedRow(String code, String takenAt) {
		OffsetDateTime firstSeenAt = OffsetDateTime.parse("2026-08-06T02:00:00Z");
		return new BrandTaggedPostRow(code, "glowdeep_92", "9001", OffsetDateTime.parse(takenAt), firstSeenAt, 7L,
				firstSeenAt, firstSeenAt, null, null);
	}

	/** direct 등록 행 — direct_registered_at만 채워지고 tag_detected_at은 null(direct-only, source=direct). */
	private static BrandTaggedPostRow directRow(String code, String takenAt) {
		OffsetDateTime firstSeenAt = OffsetDateTime.parse("2026-08-06T02:00:00Z");
		OffsetDateTime registeredAt = OffsetDateTime.parse("2026-08-07T02:00:00Z");
		return new BrandTaggedPostRow(code, "glowdeep_92", "9001", OffsetDateTime.parse(takenAt), firstSeenAt, 0L,
				registeredAt, null, registeredAt, null);
	}

	/** 겹침 행 — tag_detected_at·direct_registered_at 둘 다 채워짐(해시태그 감지 + 누군가의 direct 등록). */
	private static BrandTaggedPostRow overlapRow(String code, String takenAt) {
		OffsetDateTime firstSeenAt = OffsetDateTime.parse("2026-08-06T02:00:00Z");
		OffsetDateTime registeredAt = OffsetDateTime.parse("2026-08-07T02:00:00Z");
		return new BrandTaggedPostRow(code, "glowdeep_92", "9001", OffsetDateTime.parse(takenAt), firstSeenAt, 7L,
				registeredAt, firstSeenAt, registeredAt, null);
	}

	private static BrandHashtagPostRow hashtagRow(String code, String takenAt) {
		return hashtagRow(code, takenAt, "해시태그 캡션");
	}

	private static BrandHashtagPostRow hashtagRow(String code, String takenAt, String caption) {
		return new BrandHashtagPostRow(code, "#브랜드명", "hashtag_influencer", "해시태그 인플루언서",
				"https://cdn/hashtag-author.jpg", OffsetDateTime.parse(takenAt), caption, "REELS",
				"https://cdn/hashtag-thumb.jpg", 20L, 3L, OffsetDateTime.parse("2026-08-06T02:00:00Z"), null, null);
	}

	private static BrandPostMetaRow meta(String code, String contentType, Boolean paid) {
		return new BrandPostMetaRow(code, "glowdeep_92", contentType, LocalDate.of(2026, 8, 6),
				"캡션 원문", "https://cdn/thumb.jpg", "https://cdn/video.mp4", 15.5, paid, null,
				null, null, null);
	}

	private static BrandSnapshotRow snapshotRow(String code, int day, Long views) {
		return new BrandSnapshotRow(code, LocalDate.of(2026, 8, day), "REELS", 10L, false, 12L, views,
				5L, 7L, false, 3L);
	}
}

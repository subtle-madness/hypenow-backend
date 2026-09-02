package com.celfit.was.v1.brandmonitoring.ai;

import java.util.List;
import java.util.Map;

/**
 * 프리셋 질문 폴백(FE 변경요청서 2026-08-28 §3.1) - presetId별로 시스템 프롬프트 뒤에 덧붙이는 추가
 * 지시문. FE가 프리셋 버튼을 누르면 text(질문 문구)와 함께 presetId를 실어 보내는데, 이 지시문은
 * 모델이 그 질문 의도에 맞는 툴을 바로 골라 쓰도록 힌트를 준다.
 *
 * <p>미등록 presetId는 오류가 아니라 무시한다(자유 질의로 폴백, {@link #instructionFor}가 빈 문자열을
 * 돌려준다) - FE가 신규 프리셋을 추가했는데 백엔드가 미처 못 따라가도 요청 자체는 죽지 않는다.
 *
 * <p><b>verified 플랜(스펙 §6, Genie Trusted Assets 패턴)</b> - {@link #planFor}가 있는 프리셋은
 * 지시문만 주는 게 아니라 검증된 호출 시퀀스를 함께 부여한다. 에이전트가 루프 시작 전에 그 플랜을
 * 선실행해 결과를 대화에 주입하므로(BrandAiAgent), 이 프리셋들은 모델이 툴 선택·인자 조합을 틀릴
 * 여지가 없다. 플랜을 부여받은 프리셋의 지시문은 "핵심 데이터는 이미 조회되어 있다"는 사실을 반영해
 * 갱신했다 - 모델이 같은 조회를 또 하려 들지 않도록.
 *
 * <p><b>2026-09-02 마케터 결정 중심 6종 개편</b> - 기존 5종(efficient_influencers·top_posts·
 * sponsored_vs_organic·tagged_posts_analysis·paid_amplify)은 툴 모양에서 나온 이름이라 마케터가
 * 실제로 내리는 결정과 맞지 않는다는 판단으로, weekly_briefing·organic_fans·sponsored_scorecard·
 * ad_candidates·negative_comments·micro_creators 6종을 신설했다. 기존 5종 id는 <b>FE 전환 전까지
 * 호환 유지</b>(구 계약, FE 변경요청서 2026-08-28) - FE가 새 버튼으로 전환을 마치면 제거한다.
 */
final class BrandAiPresets {

	private static final Map<String, String> INSTRUCTIONS = Map.ofEntries(
			// --- 구 계약(08-28 FE 요청서), FE 전환 후 제거 ---
			Map.entry("efficient_influencers", """

					[프리셋] 사용자가 "효율 좋은 인플루언서" 프리셋을 선택했습니다.
					핵심 데이터는 이미 조회되어 있습니다. 그 결과로 답하되 필요하면 추가 조회하세요. 표에는 각 행의
					표본 수를 포함하세요. reachMultiple이 null인 게시자는 팔로워 정보가 없어 계산 불가라고
					밝히세요. get_author를 게시자마다 반복 호출하지 마세요.
					"""),
			Map.entry("top_posts", """

					[프리셋] 사용자가 "인기 게시물" 프리셋을 선택했습니다.
					핵심 데이터는 이미 조회되어 있습니다. 그 결과로 답하되 필요하면 추가 조회하세요. 표에는 각 행의
					표본 수를 포함하세요.
					"""),
			Map.entry("sponsored_vs_organic", """

					[프리셋] 사용자가 "협찬 vs 오가닉 비교" 프리셋을 선택했습니다.
					핵심 데이터는 이미 조회되어 있습니다. 그 결과로 답하되 필요하면 추가 조회하세요. 표에는 각 행의
					표본 수를 포함하세요. 참여율은 서버가 준 engagementRate를 그대로 인용합니다.
					"""),
			Map.entry("tagged_posts_analysis", """

					[프리셋] 사용자가 "태그된 게시물 분석" 프리셋을 선택했습니다. aggregate_posts로 규모·추이를
					잡고 list_posts·search_posts로 브랜드에 태그된 게시물의 최근 흐름과 특징(주제·언급 빈도 등)을
					정리해 답하세요.
					"""),
			Map.entry("paid_amplify", """

					[프리셋] 사용자가 "유료 증폭 후보" 프리셋을 선택했습니다. list_posts(sort=performance_desc)나
					aggregate_posts로 이미 성과가 좋은 오가닉 게시물을 찾아 유료 증폭(부스팅) 후보로 제안하세요. 실제 광고
					집행 여부는 데이터에 없으니 추천 근거는 실측 지표(조회수·참여율 등)로만 듭니다.
					"""),
			// --- 신 계약(09-02 마케터 결정 중심 6종) ---
			Map.entry("weekly_briefing", """

					[프리셋] 사용자가 "주간 브리핑" 프리셋을 선택했습니다.
					핵심 데이터는 이미 조회되어 있습니다(지난 7일 게시물 톱N과 최근 2주 주별 집계). 지난 7일 신규
					게시물 수, 조회수 톱 3(작성자·조회수·협찬 여부·shortCode 병기), 그 전 주 대비 게시물 수·조회수
					증감을 한 줄로 정리해 답하세요. 슬랙에 그대로 붙여 쓸 수 있도록 짧게 쓰고, 표는 톱 3 하나만
					두세요.
					"""),
			Map.entry("organic_fans", """

					[프리셋] 사용자가 "오가닉 팬 찾기" 프리셋을 선택했습니다.
					핵심 데이터는 이미 조회되어 있습니다(협찬 표기 없는 게시물의 작성자별 조회수 상위, 표본 수
					제한 없음). 각 행에 조회수·표본 수·팔로워를 포함하고, 게시물 1건짜리 작성자도 자발적 팬
					후보이니 제외하지 마세요. 시딩 명단에 추가할 후보라는 관점으로 마무리하세요.
					"""),
			Map.entry("sponsored_scorecard", """

					[프리셋] 사용자가 "협찬 성적표" 프리셋을 선택했습니다.
					핵심 데이터는 이미 조회되어 있습니다(협찬 표기 게시물의 작성자별 집계와, 기준선으로 쓸 협찬
					전체·오가닉 전체 평균). 표 위에 그 두 평균을 기준선으로 한 줄 밝히고, 표에서는 그 기준선보다
					잘한 작성자와 못한 작성자가 한눈에 구분되게 정리하세요. 각 행에 조회수·참여율·도달 배수·표본
					수를 포함하세요.
					"""),
			Map.entry("ad_candidates", """

					[프리셋] 사용자가 "광고 후보 게시물" 프리셋을 선택했습니다.
					핵심 데이터는 이미 조회되어 있습니다(조회수·참여율 상위 게시물). 오가닉 게시물을 우선하되
					협찬 표기 게시물도 함께 보여주세요. 작성자 집계가 아니라 게시물 단위로 각 행에 협찬 여부와
					shortCode를 병기하고, 협찬 표기 게시물은 광고로 돌리기 전 사용권 확인이 필요하다고
					표기하세요.
					"""),
			Map.entry("negative_comments", """

					[프리셋] 사용자가 "부정 댓글 점검" 프리셋을 선택했습니다.
					핵심 데이터는 이미 조회되어 있습니다(최근 30일 반응 많은 게시물 상위 5개). 그 결과의
					shortCode 전부를 get_comments에 shortCodes 배열로 한 번에 실어 호출하세요 - 게시물마다
					따로 호출하지 마세요. 댓글에서 품질·가격·배송·기대 이하·타사 비교 같은 업종 공통 부정
					신호만 추려 게시물별로 정리하세요. 특정 업종 어휘를 전제하지 마세요. 부정 반응이 없으면
					"확인한 N개 게시물 M개 댓글에서 부정 반응 없음"처럼 확실하게 답하세요.
					"""),
			Map.entry("micro_creators", """

					[프리셋] 사용자가 "마이크로 크리에이터 발굴" 프리셋을 선택했습니다.
					핵심 데이터는 이미 조회되어 있습니다(도달 배수 상위 작성자, 표본 2건 이상). 그중 팔로워 수가
					작은 순으로 상위 10명을 골라 각 행에 팔로워·평균 조회수·도달 배수·표본 수를 포함하세요.
					팔로워 정보가 없는 작성자는 제외하고 그 사실을 한 줄로 밝히세요. 저예산 시딩 후보라는
					관점으로 정리하세요.
					"""));

	/** presetId별 검증된 호출 플랜(스펙 §6) - 부여 대상이 아니면 목록에 아예 없다({@link #planFor}가
	 * 빈 목록으로 폴백). tagged_posts_analysis·paid_amplify는 정성 비중이 커서 플랜을 주지 않는다. */
	private static final Map<String, List<PlannedCall>> PLANS = Map.ofEntries(
			// --- 구 계약(08-28 FE 요청서), FE 전환 후 제거 ---
			Map.entry("efficient_influencers", List.of(new PlannedCall("aggregate_posts",
					"{\"groupBy\":\"author\",\"orderBy\":\"reachMultiple\",\"limit\":10,\"minSample\":2}"))),
			Map.entry("sponsored_vs_organic",
					List.of(new PlannedCall("aggregate_posts", "{\"groupBy\":\"sponsorship\"}"))),
			Map.entry("top_posts", List.of(new PlannedCall("list_posts", "{\"sort\":\"performance_desc\"}"))),
			// --- 신 계약(09-02 마케터 결정 중심 6종) ---
			Map.entry("weekly_briefing",
					List.of(new PlannedCall("list_posts", "{\"days\":7,\"sort\":\"performance_desc\",\"limit\":10}"),
							new PlannedCall("aggregate_posts", "{\"groupBy\":\"week\",\"days\":14}"))),
			Map.entry("organic_fans", List.of(new PlannedCall("aggregate_posts",
					"{\"groupBy\":\"author\",\"sponsorship\":\"organic\",\"orderBy\":\"avgViews\",\"limit\":10}"))),
			Map.entry("sponsored_scorecard",
					List.of(new PlannedCall("aggregate_posts",
							"{\"groupBy\":\"author\",\"sponsorship\":\"sponsored\",\"orderBy\":\"avgViews\",\"limit\":20}"),
							new PlannedCall("aggregate_posts", "{\"groupBy\":\"sponsorship\"}"))),
			Map.entry("ad_candidates",
					List.of(new PlannedCall("list_posts", "{\"sort\":\"performance_desc\",\"limit\":20}"))),
			Map.entry("negative_comments", List.of(
					new PlannedCall("list_posts", "{\"days\":30,\"sort\":\"performance_desc\",\"limit\":5}"))),
			Map.entry("micro_creators", List.of(new PlannedCall("aggregate_posts",
					"{\"groupBy\":\"author\",\"orderBy\":\"reachMultiple\",\"limit\":30,\"minSample\":2}"))));

	private BrandAiPresets() {
	}

	/** 등록된 presetId면 지시문, 아니면(null·미등록) 빈 문자열 - 호출부가 항상 시스템 프롬프트에 그대로 이어붙일 수 있게 한다. */
	static String instructionFor(String presetId) {
		if (presetId == null) {
			return "";
		}
		return INSTRUCTIONS.getOrDefault(presetId, "");
	}

	/** 검증된 호출 플랜 1건(스펙 §6) - argsJson의 brandId는 실행 시 세션 brandId로 치환된다(고정값을
	 * 하드코딩하지 않는다). */
	record PlannedCall(String toolName, String argsJson) {
	}

	/** presetId의 verified 플랜 - 없으면(null·미등록·플랜 미보유) 빈 목록. 빈 목록이면 에이전트는 선실행을
	 * 건너뛰고 기존 자유 경로 그대로 동작한다. */
	static List<PlannedCall> planFor(String presetId) {
		if (presetId == null) {
			return List.of();
		}
		return PLANS.getOrDefault(presetId, List.of());
	}
}

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
 */
final class BrandAiPresets {

	private static final Map<String, String> INSTRUCTIONS = Map.of(
			"efficient_influencers", """

					[프리셋] 사용자가 "효율 좋은 인플루언서" 프리셋을 선택했습니다.
					핵심 데이터는 이미 조회되어 있습니다. 그 결과로 답하되 필요하면 추가 조회하세요. 표에는 각 행의
					표본 수를 포함하세요. reachMultiple이 null인 게시자는 팔로워 정보가 없어 계산 불가라고
					밝히세요. get_author를 게시자마다 반복 호출하지 마세요.
					""",
			"top_posts", """

					[프리셋] 사용자가 "인기 게시물" 프리셋을 선택했습니다.
					핵심 데이터는 이미 조회되어 있습니다. 그 결과로 답하되 필요하면 추가 조회하세요. 표에는 각 행의
					표본 수를 포함하세요.
					""",
			"sponsored_vs_organic", """

					[프리셋] 사용자가 "협찬 vs 오가닉 비교" 프리셋을 선택했습니다.
					핵심 데이터는 이미 조회되어 있습니다. 그 결과로 답하되 필요하면 추가 조회하세요. 표에는 각 행의
					표본 수를 포함하세요. 참여율은 서버가 준 engagementRate를 그대로 인용합니다.
					""",
			"tagged_posts_analysis", """

					[프리셋] 사용자가 "태그된 게시물 분석" 프리셋을 선택했습니다. aggregate_posts로 규모·추이를
					잡고 list_posts·search_posts로 브랜드에 태그된 게시물의 최근 흐름과 특징(주제·언급 빈도 등)을
					정리해 답하세요.
					""",
			"paid_amplify", """

					[프리셋] 사용자가 "유료 증폭 후보" 프리셋을 선택했습니다. list_posts(sort=performance_desc)나
					aggregate_posts로 이미 성과가 좋은 오가닉 게시물을 찾아 유료 증폭(부스팅) 후보로 제안하세요. 실제 광고
					집행 여부는 데이터에 없으니 추천 근거는 실측 지표(조회수·참여율 등)로만 듭니다.
					""");

	/** presetId별 검증된 호출 플랜(스펙 §6) - 부여 대상이 아니면 목록에 아예 없다({@link #planFor}가
	 * 빈 목록으로 폴백). tagged_posts_analysis·paid_amplify는 정성 비중이 커서 플랜을 주지 않는다. */
	private static final Map<String, List<PlannedCall>> PLANS = Map.of(
			"efficient_influencers", List.of(new PlannedCall("aggregate_posts",
					"{\"groupBy\":\"author\",\"orderBy\":\"reachMultiple\",\"limit\":10,\"minSample\":2}")),
			"sponsored_vs_organic", List.of(new PlannedCall("aggregate_posts", "{\"groupBy\":\"sponsorship\"}")),
			"top_posts", List.of(new PlannedCall("list_posts", "{\"sort\":\"performance_desc\"}")));

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

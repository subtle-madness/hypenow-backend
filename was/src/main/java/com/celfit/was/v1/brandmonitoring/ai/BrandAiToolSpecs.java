package com.celfit.was.v1.brandmonitoring.ai;

import java.util.List;
import java.util.Map;

/**
 * 툴 선언 8종(설계 §4, 2026-08-28 search_posts·aggregate_posts 신설) - 전부 읽기 전용이다. 이름
 * 문자열이 {@link BrandAiToolbox}의 dispatch switch와 1:1로 맞아야 하므로 상수로 뽑아 양쪽이 같은
 * 심볼을 쓴다.
 *
 * <p>description은 모델이 읽는 유일한 사용 설명서다 - 상한(게시물 30건·댓글 50건)과 데이터의
 * 한계(해시태그 발견 게시물은 지표·댓글이 없다)를 여기 적어야 모델이 헛도는 호출을 하지 않는다.
 * search_posts·aggregate_posts의 description은 특히 "list_posts로 세지 마라"를 명시한다 - 30건
 * 발췌 목록을 모델이 눈으로 세다 273건 중 85건을 0건으로 답한 실측 오답이 이 신설의 배경이다.
 *
 * <p><b>2026-08-28 기간 기본값 수정</b> - list_posts·search_posts·aggregate_posts의 days는 더 이상
 * 생략 시 30일로 채워지지 않는다({@link BrandAiToolbox#resolveWindow} 참조). 기간을 말하지 않은
 * 질문의 자연스러운 의미는 "수집된 전체"라, 세 툴 모두 description에 "days 생략 시 수집 기간 전체를
 * 대상으로 한다. 사용자가 기간을 명시했을 때만 days를 넘겨라"를 명시해 모델이 먼저 나서서 30일을
 * 채워 넣지 않게 한다.
 */
public final class BrandAiToolSpecs {

	public static final String LIST_BRANDS = "list_brands";
	public static final String LIST_POSTS = "list_posts";
	public static final String SEARCH_POSTS = "search_posts";
	public static final String AGGREGATE_POSTS = "aggregate_posts";
	public static final String GET_POST = "get_post";
	public static final String GET_COMMENTS = "get_comments";
	public static final String LIST_HASHTAG_POSTS = "list_hashtag_posts";
	public static final String GET_AUTHOR = "get_author";

	public static final List<AiToolSpec> ALL = List.of(
			new AiToolSpec(LIST_BRANDS,
					"이 사용자가 모니터링 중인 브랜드 계정 목록과 계정 메타(팔로워·게시물 수·소개글·내 브랜드/경쟁사 구분)를 돌려준다. "
							+ "brandId가 필요한 다른 툴을 쓰기 전에 먼저 호출한다.",
					null),
			new AiToolSpec(LIST_POSTS,
					"브랜드에 태그된 게시물 목록을 최근 순 또는 성과 순으로 기본 30건, limit 인자로 최대 100건까지 돌려준다. "
							+ "각 항목은 shortCode·업로드일·유료협찬 표기 여부·캡션 앞부분·좋아요/댓글수/조회수·작성자 "
							+ "팔로워 수(authorFollowers, 미수집이면 null)를 담는다. "
							+ "피드 게시물의 조회수는 항상 null이다. 최근 흐름을 훑어보거나 톱N을 뽑을 때 쓰고, "
							+ "캡션에서 제품명·키워드 언급을 세거나 찾을 때는 절대 이 툴로 세지 말고 search_posts를 쓴다. "
							+ "광고·협찬 게시물만 보려면 캡션에서 '광고'라는 글자를 찾지 말고 sponsorship 인자를 쓴다 - "
							+ "협찬 표기 판정은 캡션 문자열 검색이 아니라 서버가 계산한 축이다. "
							+ "특정 작성자(인플루언서)의 게시물만 볼 때는 author 인자를 쓴다 - shortCode를 몰라도, "
							+ "사용자에게 shortCode를 요구하지 않아도 작성자 아이디만으로 그 사람 게시물만 걸러 볼 수 있다. "
							+ "days 생략 시 수집 기간 전체를 대상으로 한다. 사용자가 기간을 명시했을 때만 days를 넘겨라.",
					"""
					{"type":"object","properties":{
					  "brandId":{"type":"integer","description":"list_brands가 돌려준 브랜드 id"},
					  "days":{"type":"integer","description":"오늘부터 며칠 전까지 볼지. 생략하면 수집된 기간 전체, 최대 365일"},
					  "sort":{"type":"string","enum":["uploaded_desc","performance_desc"],
					          "description":"uploaded_desc는 최신순, performance_desc는 조회수 높은 순. 생략하면 최신순"},
					  "sponsorship":{"type":"string","enum":["sponsored","organic","unknown"],
					                 "description":"협찬 표기 축 필터 - sponsored=협찬표기, organic=오가닉, unknown=판정미상. 광고·협찬 게시물 질문은 keyword가 아니라 이 인자로 거른다"},
					  "author":{"type":"string","description":"특정 작성자(인플루언서)의 게시물만 볼 때 그 인스타그램 아이디를 넣는다(@ 있어도 없어도 됨, 대소문자 무시, 정확 일치). shortCode를 몰라도 이 인자로 바로 좁혀 조회한다"},
					  "limit":{"type":"integer","description":"돌려줄 게시물 수. 생략하면 30, 최대 100. 사용자가 N개를 명시하면 그 값을 그대로 넘겨라(최대 100까지)"}
					},"required":["brandId"]}
					"""),
			new AiToolSpec(SEARCH_POSTS,
					"브랜드 게시물 캡션에서 제품명·키워드 언급을 검색한다. list_posts와 달리 최근 30건이 아니라 "
							+ "지정한 기간(창) 안 전체 게시물을 대상으로 정확한 총 매칭 건수(totalMatches)를 상한 없이 돌려준다. "
							+ "캡션에서 특정 단어를 몇 번 언급했는지 세거나 찾는 질문에는 반드시 이 툴을 쓴다 - "
							+ "list_posts로 세면 30건을 넘는 언급을 놓친다. 상세(캡션 발췌·게시자·작성자 팔로워 수·최신 좋아요/조회수)는 "
							+ "매칭 상위 20건만 담기지만 totalMatches 숫자는 그대로 인용한다. 검색어의 공백 유무는 흡수한다. "
							+ "query는 캡션 문자 매칭일 뿐 광고·협찬 여부 판정이 아니다 - 광고·협찬 게시물을 찾을 때는 "
							+ "query에 '광고'를 넣지 말고 sponsorship 인자를 쓴다(query와 함께 쓰면 교집합). "
							+ "특정 작성자(인플루언서)의 게시물 안에서만 찾을 때는 author 인자를 함께 쓴다(query와 교집합). "
							+ "days 생략 시 수집 기간 전체를 대상으로 한다. 사용자가 기간을 명시했을 때만 days를 넘겨라.",
					"""
					{"type":"object","properties":{
					  "brandId":{"type":"integer","description":"list_brands가 돌려준 브랜드 id"},
					  "query":{"type":"string","description":"캡션에서 찾을 제품명·키워드"},
					  "days":{"type":"integer","description":"오늘부터 며칠 전까지 볼지. 생략하면 수집된 기간 전체, 최대 365일"},
					  "sponsorship":{"type":"string","enum":["sponsored","organic","unknown"],
					                 "description":"협찬 표기 축 필터 - sponsored=협찬표기, organic=오가닉, unknown=판정미상. query와 함께 쓰면 그 조건까지 교집합"},
					  "author":{"type":"string","description":"특정 작성자(인플루언서)의 게시물 안에서만 검색할 때 그 인스타그램 아이디(@ 있어도 없어도 됨, 대소문자 무시, 정확 일치). query와 함께 쓰면 교집합"}
					},"required":["brandId","query"]}
					"""),
			new AiToolSpec(AGGREGATE_POSTS,
					"브랜드 게시물의 수·좋아요/댓글/조회수 합계·평균을 지정한 기간(창) 안 전체를 대상으로 집계한다. "
							+ "groupBy를 주면 작성자별·월별·주별·협찬여부별·미디어타입별로 묶어 그룹별 집계와 서버가 계산한 "
							+ "파생 지표(author 축: followers·reachMultiple=릴스 평균 조회수÷팔로워·engagementRate=릴스 댓글 합÷조회수 합)를 "
							+ "orderBy 기준 내림차순 정렬로 돌려준다. 작성자 랭킹·기간 비교·협찬 vs 오가닉 비교는 반드시 이 툴 "
							+ "1회로 해결한다 - list_posts로 모아 get_author를 반복 호출하며 직접 계산하지 마라. "
							+ "reachMultiple·engagementRate·totalGroups 등 숫자는 직접 재계산하지 말고 그대로 인용한다. "
							+ "keyword를 주면 캡션에 그 키워드가 있는 게시물만 모수로 삼는다 - keyword는 캡션 문자 매칭일 "
							+ "뿐 광고·협찬 여부 판정이 아니다. '광고 게시물' 같은 질문은 keyword로 캡션을 세지 말고 반드시 "
							+ "sponsorship 인자를 쓴다(협찬 표기 축, 서버가 판정). "
							+ "조회수·도달배수·참여율은 릴스만 집계한다(피드는 조회수가 항상 없다). "
							+ "groupBy 시 minSample로 그룹의 릴스 표본 수 하한을 걸 수 있다 - 표본 1개짜리 극단값이 랭킹 "
							+ "상위를 도배하는 걸 막고 싶을 때만 쓰고, '1개짜리라도 보여줘' 같은 질문에는 생략한다. "
							+ "limit 초과분은 잘리고 totalGroups로 전체 수를 알려주니 '전체 N개 중 상위 M개 기준'을 답변에 명시하라. "
							+ "특정 작성자(인플루언서) 한 명의 게시물만 모수로 집계할 때는 author 인자를 쓴다(groupBy=author와는 "
							+ "다르다 - groupBy=author는 전체 작성자를 묶어 비교하고, author는 그 한 사람으로 모수를 좁힌다). "
							+ "days 생략 시 수집 기간 전체를 대상으로 한다. 사용자가 기간을 명시했을 때만 days를 넘겨라.",
					"""
					{"type":"object","properties":{
					  "brandId":{"type":"integer","description":"list_brands가 돌려준 브랜드 id"},
					  "days":{"type":"integer","description":"오늘부터 며칠 전까지 볼지. 생략하면 수집된 기간 전체, 최대 365일"},
					  "keyword":{"type":"string","description":"캡션 필터 - 이 키워드가 캡션에 있는 게시물만 집계. 공백 유무는 흡수. 광고·협찬 여부 판정에는 쓰지 마라(sponsorship 인자를 쓴다)"},
					  "sponsorship":{"type":"string","enum":["sponsored","organic","unknown"],
					                 "description":"협찬 표기 축 필터 - sponsored=협찬표기, organic=오가닉, unknown=판정미상. '광고 게시물'류 질문은 keyword가 아니라 이 인자로 거른다"},
					  "author":{"type":"string","description":"특정 작성자(인플루언서) 한 명의 게시물로만 모수를 좁혀 집계할 때 그 인스타그램 아이디(@ 있어도 없어도 됨, 대소문자 무시, 정확 일치)"},
					  "groupBy":{"type":"string","enum":["author","month","week","sponsorship","mediaType"],
					             "description":"묶는 축. author=작성자별, month/week=KST 달력 월/주별(기간 비교용), sponsorship=협찬여부별, mediaType=릴스/피드별. 생략하면 전체 하나로 집계"},
					  "orderBy":{"type":"string","enum":["postCount","totalViews","avgViews","avgLikes","avgComments","reachMultiple","engagementRate"],
					             "description":"그룹 정렬 기준(내림차순, 서버 정렬). 생략하면 postCount"},
					  "limit":{"type":"integer","description":"돌려줄 그룹 상위 N. 생략하면 10, 최대 50. 사용자가 N명/N개를 명시하면 그 값을 그대로 넘겨라"},
					  "minSample":{"type":"integer","description":"groupBy 시 그룹의 릴스 표본 수(viewsSampleCount) 하한. 미달 그룹은 제외되고 filteredOutBySample로 보고된다. 생략하면 제한 없음"}
					},"required":["brandId"]}
					"""),
			new AiToolSpec(GET_POST,
					"게시물 1건의 상세를 돌려준다: 전체 캡션, 게시자, 작성자 팔로워 수, 유료협찬 표기 여부, 광고 표기 판정, 일별 지표 시계열(최근 14일). "
							+ "shortCode는 list_posts가 돌려준 값이어야 한다. 해시태그로 발견한 게시물(list_hashtag_posts)은 "
							+ "브랜드 게시물 풀에 없어 이 툴로 조회되지 않는다.",
					"""
					{"type":"object","properties":{
					  "shortCode":{"type":"string","description":"인스타그램 게시물 shortCode"}
					},"required":["shortCode"]}
					"""),
			new AiToolSpec(GET_COMMENTS,
					"게시물의 댓글을 최신순으로 돌려준다. shortCodes 배열로 최대 5개 게시물을 한 번에 조회할 수 있다 - "
							+ "여러 게시물의 댓글 여론을 종합할 때 게시물마다 따로 호출하지 말고 반드시 배열로 묶어 1회 호출한다. "
							+ "배열 호출은 게시물당 기본 10건(최대 20건), 전체 최대 50건. 단일 게시물만 볼 때는 shortCode 하나로 "
							+ "호출하면 기본 20건(최대 50건)이다.",
					"""
					{"type":"object","properties":{
					  "shortCodes":{"type":"array","items":{"type":"string"},
					                "description":"인스타그램 게시물 shortCode 목록(최대 5개) - 여러 게시물 댓글 종합 시 사용"},
					  "shortCode":{"type":"string","description":"단일 게시물 shortCode(shortCodes 대신 사용 가능)"},
					  "limit":{"type":"integer","description":"게시물당 가져올 댓글 수. 배열이면 기본 10·최대 20, 단건이면 기본 20·최대 50"}
					}}
					"""),
			new AiToolSpec(LIST_HASHTAG_POSTS,
					"브랜드 해시태그로 발견한 게시물을 최근 순으로 최대 30건 돌려준다. 태그 없이 브랜드를 언급한 게시물을 찾는 경로다. "
							+ "이 게시물들은 지표 시계열과 댓글이 수집되지 않아 get_post·get_comments로 더 파고들 수 없다.",
					"""
					{"type":"object","properties":{
					  "brandId":{"type":"integer","description":"list_brands가 돌려준 브랜드 id"},
					  "days":{"type":"integer","description":"오늘부터 며칠 전까지 볼지. 생략하면 30일, 최대 365일"}
					},"required":["brandId"]}
					"""),
			new AiToolSpec(GET_AUTHOR,
					"게시자(인플루언서) 인스타그램 계정의 공개 프로필을 돌려준다: 이름·팔로워 수·인증 배지 여부. "
							+ "list_posts가 돌려준 authorUsername으로 호출한다.",
					"""
					{"type":"object","properties":{
					  "username":{"type":"string","description":"인스타그램 계정 아이디(@ 없이)"}
					},"required":["username"]}
					"""));

	/** 미등록 툴(신규 추가 시 아직 라벨을 못 붙인 경우)에 쓰는 기본 진행 문구(2026-09-02, SSE status 확장). */
	private static final String DEFAULT_LABEL = "조회하는 중";

	/** 툴별 SSE status 진행 문구(2026-09-02, SSE status 확장) - FE 진행 표시가 읽는 정본이라 한 곳에
	 * 모아둔다. 신규 툴을 추가하면 여기에도 항목을 더해야 한다(빠뜨리면 {@link #DEFAULT_LABEL}로 대체). */
	private static final Map<String, String> LABELS = Map.of(
			LIST_BRANDS, "브랜드 확인하는 중",
			LIST_POSTS, "게시물 찾는 중",
			SEARCH_POSTS, "캡션 검색하는 중",
			AGGREGATE_POSTS, "집계하는 중",
			GET_POST, "게시물 상세 확인하는 중",
			GET_COMMENTS, "댓글 읽는 중",
			LIST_HASHTAG_POSTS, "해시태그 게시물 찾는 중",
			GET_AUTHOR, "작성자 프로필 확인하는 중");

	/** toolName에 해당하는 SSE status 진행 문구를 돌려준다. 미등록 툴이면 {@link #DEFAULT_LABEL}. */
	public static String labelFor(String toolName) {
		return LABELS.getOrDefault(toolName, DEFAULT_LABEL);
	}

	private BrandAiToolSpecs() {
	}
}

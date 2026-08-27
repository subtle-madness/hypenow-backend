package com.celfit.was.v1.brandmonitoring.ai;

import java.util.List;

/**
 * 툴 선언 6종(설계 §4) - 전부 읽기 전용이다. 이름 문자열이 {@link BrandAiToolbox}의 dispatch
 * switch와 1:1로 맞아야 하므로 상수로 뽑아 양쪽이 같은 심볼을 쓴다.
 *
 * <p>description은 모델이 읽는 유일한 사용 설명서다 - 상한(게시물 30건·댓글 50건)과 데이터의
 * 한계(해시태그 발견 게시물은 지표·댓글이 없다)를 여기 적어야 모델이 헛도는 호출을 하지 않는다.
 */
public final class BrandAiToolSpecs {

	public static final String LIST_BRANDS = "list_brands";
	public static final String LIST_POSTS = "list_posts";
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
					"브랜드에 태그된 게시물 목록을 최근 순 또는 성과 순으로 최대 30건 돌려준다. "
							+ "각 항목은 shortCode·업로드일·유료협찬 표기 여부·캡션 앞부분·좋아요/댓글수/조회수를 담는다. "
							+ "피드 게시물의 조회수는 항상 null이다.",
					"""
					{"type":"object","properties":{
					  "brandId":{"type":"integer","description":"list_brands가 돌려준 브랜드 id"},
					  "days":{"type":"integer","description":"오늘부터 며칠 전까지 볼지. 생략하면 30일, 최대 365일"},
					  "sort":{"type":"string","enum":["uploaded_desc","performance_desc"],
					          "description":"uploaded_desc는 최신순, performance_desc는 조회수 높은 순. 생략하면 최신순"}
					},"required":["brandId"]}
					"""),
			new AiToolSpec(GET_POST,
					"게시물 1건의 상세를 돌려준다: 전체 캡션, 게시자, 유료협찬 표기 여부, 광고 표기 판정, 일별 지표 시계열(최근 14일). "
							+ "shortCode는 list_posts가 돌려준 값이어야 한다. 해시태그로 발견한 게시물(list_hashtag_posts)은 "
							+ "브랜드 게시물 풀에 없어 이 툴로 조회되지 않는다.",
					"""
					{"type":"object","properties":{
					  "shortCode":{"type":"string","description":"인스타그램 게시물 shortCode"}
					},"required":["shortCode"]}
					"""),
			new AiToolSpec(GET_COMMENTS,
					"게시물 1건의 댓글을 최신순으로 돌려준다. 최대 50건이며 그보다 큰 limit을 넘겨도 50건으로 자른다. "
							+ "댓글 반응·여론을 물었을 때 쓴다.",
					"""
					{"type":"object","properties":{
					  "shortCode":{"type":"string","description":"인스타그램 게시물 shortCode"},
					  "limit":{"type":"integer","description":"가져올 댓글 수. 생략하면 20건, 최대 50건"}
					},"required":["shortCode"]}
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

	private BrandAiToolSpecs() {
	}
}

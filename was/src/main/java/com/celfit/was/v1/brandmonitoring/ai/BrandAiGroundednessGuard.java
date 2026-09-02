package com.celfit.was.v1.brandmonitoring.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * 날조(ungrounded) 답변 판정(서버 groundedness 가드, 2026-09-02 - "가벼운 층" 재설계) - 스펙 §4
 * "고지는 서버 강제"의 연장이다. 질문 "시딩 우선순위 정하려는데 기준 좀 잡아줘"에 gemini-3.1-flash-lite가
 * 툴을 한 번도 호출하지 않은 채 가짜 계정명·수치 표를 날조한 사례가 있었다 - BrandAiGlossary 지시를
 * 2차례 강화해도 재현돼 프롬프트 계층으로는 못 막는 사례로 확인됐다.
 *
 * <p><b>2026-09-02 재설계</b> - 최초 신호("툴 호출 0회 + 표 또는 세션 브랜드가 아닌 @핸들")는 툴을 1회라도
 * 부르면 무조건 통과시켜, 답변에 실린 계정명이 실제로 그 호출 결과에 있었는지는 보지 않았다. 이제 이번
 * run()에서 실행된 툴 결과 페이로드에 실제로 등장한 계정명 집합({@code groundedHandles})과 답변 속
 * 계정명 후보를 대조한다 - 집합에도 세션 브랜드에도 없는 계정명이 하나라도 있으면 날조로 본다. 툴
 * 호출이 0회면 집합이 비어 있으므로 기존 규칙(표 또는 @핸들 존재)이 자연히 그대로 포함된다.
 *
 * <p>{@link BrandAiAgent} 루프에서 떼어낸 순수 함수라 입출력만으로 단위 테스트한다.
 */
final class BrandAiGroundednessGuard {

	/** 줄 시작 "|"로 열고 "|"로 닫는 행 - 마크다운 표 행(헤더·구분선·데이터 행 모두 포함)만 잡는다. */
	private static final Pattern MARKDOWN_TABLE_ROW = Pattern.compile("(?m)^\\s*\\|.*\\|\\s*$");

	/** 표 구분선 행("---", ":--:" 등) - 데이터 행이 아니므로 셀 후보 추출에서 제외한다. */
	private static final Pattern TABLE_SEPARATOR_ROW = Pattern.compile("^:?-+:?$");

	/** 인스타그램 계정명 형태의 @핸들. */
	private static final Pattern HANDLE = Pattern.compile("@[A-Za-z0-9_.]{3,}");

	/** 작은따옴표·큰따옴표·백틱으로 감싼 계정명 형태 토큰(2026-09-02 갭 보완) - 골드셋
	 * chain-referent-resolution 실측 실패("작성자 'laura.acds'의 프로필 정보입니다"처럼 계정명을
	 * 따옴표로만 감싸 @핸들도 표 셀도 아닌 채 날조된 사례, 4회 중 2회 재현)를 잡는다. 여는 따옴표와
	 * 닫는 따옴표가 같은 문자여야 매치되도록 그룹 1을 역참조한다(group 1 = 따옴표 문자, group 2 =
	 * 후보). 소문자·숫자·점·밑줄만 허용해 대문자가 섞인 shortCode("DcG1oOthloi")나 한글·공백이 섞인
	 * 일반 문구("설명 부족")는 자연히 제외된다. */
	private static final Pattern QUOTED_ACCOUNT_NAME = Pattern.compile("(['\"`])([a-z0-9_.]{3,30})\\1");

	/** 표 셀 중 계정명 형태(소문자·숫자·점·밑줄만, 문자·밑줄을 최소 1개는 포함) - "14520"·"1.82"처럼
	 * 숫자(소수점 포함)만인 셀은 문자·밑줄이 없어 제외된다(조회수·참여율 등 수치 셀 오탐 방지). */
	private static final Pattern CELL_ACCOUNT_NAME = Pattern.compile("^(?=[a-z0-9_.]{3,}$)[0-9.]*[a-z_][a-z0-9_.]*$");

	/** 계정명을 담는 필드명(2026-09-02, {@link BrandAiToolbox} 페이로드 조립부 실측) - "authorUsername"
	 * (list_posts·search_posts·get_post·list_hashtag_posts의 게시자), "username"(list_brands·
	 * get_author), "author"(get_comments 댓글 작성자 및 aggregate_posts groupBy=author 그룹의 계정명 -
	 * 그룹 키 자체는 범용 "key" 필드라 다른 groupBy와 필드명이 겹치지만, {@link BrandAiToolbox}가
	 * groupBy=author일 때만 같은 값을 "author" 필드로도 명시해 이 집합이 그대로 잡게 한다, 2026-09-02
	 * 갭 보완 - 그러지 않으면 인플루언서 랭킹 답변마다 불필요한 재시도가 돌았다). */
	private static final Set<String> ACCOUNT_NAME_FIELDS = Set.of("authorUsername", "username", "author");

	private BrandAiGroundednessGuard() {
	}

	/**
	 * 툴 결과 페이로드에서 계정명을 재귀적으로 모은다(2026-09-02 재설계) - {@link #ACCOUNT_NAME_FIELDS}에
	 * 속한 필드의 문자열 값을 전부 {@code out}에 더한다. {@link BrandAiAgent}가 이번 run() 동안 실행된
	 * 모든 성공 툴 호출 결과에 대해 누적 호출해 groundedHandles 집합을 쌓는다.
	 */
	static void collectGroundedHandles(JsonNode node, Set<String> out) {
		if (node == null) {
			return;
		}
		if (node.isObject()) {
			for (Map.Entry<String, JsonNode> entry : node.properties()) {
				JsonNode value = entry.getValue();
				if (value.isString() && ACCOUNT_NAME_FIELDS.contains(entry.getKey())) {
					String text = value.asString();
					if (text != null && !text.isBlank()) {
						out.add(text);
					}
				} else {
					collectGroundedHandles(value, out);
				}
			}
		} else if (node.isArray()) {
			for (JsonNode child : node) {
				collectGroundedHandles(child, out);
			}
		}
	}

	/** 판정 결과 - ungrounded면 답변 속에서 근거를 못 찾은 계정명 후보 목록을 함께 돌려준다(재시도
	 * 지시문·warn 로그용). 근거가 있으면(false) unmatchedHandles는 항상 빈 목록이다. */
	record Result(boolean ungrounded, List<String> unmatchedHandles) {

		private static final Result GROUNDED = new Result(false, List.of());
	}

	/**
	 * @param answer                모델이 낸 최종 답변 텍스트.
	 * @param toolCallCountThisTurn 이번 실행(run() 1회, 프리셋 선실행 주입분 포함)에서 지금까지 실행된
	 *                               툴 호출 총수 - 로그·시그니처 일관성용으로 받는다. 판정 로직 자체는
	 *                               groundedHandles로 대체됐다(툴 호출 0회면 이 집합이 항상 비어 있어
	 *                               결과가 같다).
	 * @param sessionBrandUsername   세션에 고정된 브랜드의 username(대소문자 무시 비교) - 답변에 이
	 *                               핸들만 나오면 정상 자기 언급으로 보고 예외로 둔다. null이면 예외
	 *                               없이 등장하는 모든 @핸들을 날조 신호로 본다.
	 * @param groundedHandles        이번 run()에서 실행된 툴 결과 페이로드에 실제로 등장한 계정명 집합
	 *                                (대소문자 무시 비교, {@link BrandAiAgent}가 누적한다). 답변 속
	 *                                계정명 후보가 이 집합에도 세션 브랜드에도 없으면 날조로 본다.
	 */
	static Result ungrounded(String answer, int toolCallCountThisTurn, String sessionBrandUsername,
			Set<String> groundedHandles) {
		if (answer == null || answer.isBlank()) {
			return Result.GROUNDED;
		}
		boolean hasTable = MARKDOWN_TABLE_ROW.matcher(answer).find();
		boolean hasHandle = HANDLE.matcher(answer).find();
		boolean hasQuoted = QUOTED_ACCOUNT_NAME.matcher(answer).find();
		// 최초 신호(표 또는 @핸들 또는 따옴표 감싼 계정명 형태)가 없으면 애초에 날조 의심 대상이
		// 아니다 - 104턴 스윕 실측 오탐 0의 근거인 신호라 toolCallCountThisTurn 여부와 무관하게 좁혀
		// 유지한다.
		if (!hasTable && !hasHandle && !hasQuoted) {
			return Result.GROUNDED;
		}

		Set<String> normalizedGrounded = new LinkedHashSet<>();
		if (groundedHandles != null) {
			for (String handle : groundedHandles) {
				if (handle != null && !handle.isBlank()) {
					normalizedGrounded.add(handle.toLowerCase(Locale.ROOT));
				}
			}
		}
		String sessionHandle = sessionBrandUsername == null ? null
				: sessionBrandUsername.toLowerCase(Locale.ROOT);

		LinkedHashSet<String> unmatched = new LinkedHashSet<>();
		Matcher handleMatcher = HANDLE.matcher(answer);
		while (handleMatcher.find()) {
			String candidate = handleMatcher.group().substring(1); // "@" 제거
			String normalized = candidate.toLowerCase(Locale.ROOT);
			if (normalized.equals(sessionHandle) || normalizedGrounded.contains(normalized)) {
				continue;
			}
			unmatched.add(candidate);
		}
		for (String cell : tableDataCells(answer)) {
			String normalized = cell.toLowerCase(Locale.ROOT);
			if (normalized.equals(sessionHandle) || normalizedGrounded.contains(normalized)) {
				continue;
			}
			unmatched.add(cell);
		}
		Matcher quotedMatcher = QUOTED_ACCOUNT_NAME.matcher(answer);
		while (quotedMatcher.find()) {
			String candidate = quotedMatcher.group(2); // 이미 소문자만 매치되므로 그대로 정규화값
			if (candidate.equals(sessionHandle) || normalizedGrounded.contains(candidate)) {
				continue;
			}
			unmatched.add(candidate);
		}

		if (unmatched.isEmpty()) {
			return Result.GROUNDED;
		}
		return new Result(true, List.copyOf(unmatched));
	}

	/**
	 * 마크다운 표의 데이터 행 셀 중 계정명 형태({@link #CELL_ACCOUNT_NAME})인 것만 후보로 뽑는다.
	 * 구분선 행("| --- | --- |")은 제외한다.
	 */
	private static List<String> tableDataCells(String answer) {
		List<String> cells = new ArrayList<>();
		Matcher rowMatcher = MARKDOWN_TABLE_ROW.matcher(answer);
		while (rowMatcher.find()) {
			String row = rowMatcher.group().trim();
			String inner = row.substring(1, row.length() - 1); // 앞뒤 "|" 제거
			String[] rawCells = inner.split("\\|", -1);
			boolean isSeparatorRow = true;
			for (String rawCell : rawCells) {
				String cell = rawCell.trim();
				if (!cell.isEmpty() && !TABLE_SEPARATOR_ROW.matcher(cell).matches()) {
					isSeparatorRow = false;
					break;
				}
			}
			if (isSeparatorRow) {
				continue;
			}
			for (String rawCell : rawCells) {
				String cell = rawCell.trim();
				String withoutAt = cell.startsWith("@") ? cell.substring(1) : cell;
				if (CELL_ACCOUNT_NAME.matcher(withoutAt).matches()) {
					cells.add(withoutAt);
				}
			}
		}
		return cells;
	}
}

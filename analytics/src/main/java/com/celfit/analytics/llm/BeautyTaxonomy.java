package com.celfit.analytics.llm;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 분류 어휘의 단일 원천 — celfit-front 배포본(2026-07-14) 필터 어휘와 1:1.
 * 분류값·라벨은 생산자(분석 층)가 확정하고 was는 verbatim 전달만 한다(ARCHITECTURE §4-4).
 * 프론트 mid/sub 필터는 sub_categories 배열 포함 여부로 매칭하므로
 * 중분류·소분류 라벨 표기가 한 글자라도 다르면 필터가 빈다 — 여기 상수를 프론트와 함께 갱신할 것.
 */
public final class BeautyTaxonomy {

	private record Mid(String label, List<String> subs) {
	}

	private record Main(String value, String label, List<Mid> mids) {
	}

	private static final List<Main> TREE = List.of(
			new Main("skincare", "스킨케어", List.of(
					new Mid("스킨/토너", List.of("스킨", "토너")),
					new Mid("에센스/세럼/앰플", List.of("에센스", "세럼", "앰플")),
					new Mid("크림", List.of("크림", "아이크림")),
					new Mid("로션", List.of("로션", "올인원")),
					new Mid("미스트/오일", List.of("미스트", "페이스오일")))),
			new Main("suncare", "선케어", List.of(
					new Mid("선크림", List.of("선크림")),
					new Mid("선스틱", List.of("선스틱")),
					new Mid("선쿠션", List.of("선쿠션")),
					new Mid("선스프레이/선패치", List.of("선스프레이", "선패치")),
					new Mid("태닝/애프터선", List.of("태닝", "애프터선")))),
			new Main("makeup", "메이크업", List.of(
					new Mid("립메이크업", List.of("립틴트", "립스틱", "립라이너", "립케어", "컬러립밤", "립글로스")),
					new Mid("베이스메이크업", List.of("쿠션", "파운데이션", "블러셔", "파우더", "팩트", "컨실러",
							"프라이머", "쉐딩", "하이라이터", "메이크업 픽서")),
					new Mid("아이메이크업", List.of("아이라이너", "마스카라", "아이브로우", "아이섀도우", "아이래쉬 케어")))),
			new Main("cleansing", "클렌징", List.of(
					new Mid("클렌징폼/젤", List.of("클렌징폼", "클렌징젤", "팩클렌저", "클렌징 비누")),
					new Mid("오일/밤", List.of("클렌징오일", "클렌징밤")),
					new Mid("워터/밀크", List.of("클렌징워터", "클렌징밀크", "클렌징크림")),
					new Mid("필링&스크럽", List.of("스크럽", "필링", "파우더워시")),
					new Mid("티슈/패드", List.of("클렌징티슈", "클렌징패드")),
					new Mid("립&아이리무버", List.of("립&아이리무버")))),
			new Main("haircare", "헤어케어", List.of(
					new Mid("샴푸/스케일러", List.of("샴푸")),
					new Mid("트리트먼트/팩", List.of("린스", "컨디셔너", "헤어 트리트먼트", "헤어팩", "노워시 트리트먼트")),
					new Mid("두피에센스", List.of("두피토닉", "두피앰플")),
					new Mid("헤어에센스", List.of("헤어세럼", "헤어오일")))),
			new Main("fragrance", "향수/디퓨저", List.of(
					new Mid("향수", List.of("향수", "헤어퍼퓸")),
					new Mid("홈프래그런스", List.of("디퓨저", "캔들", "인센스", "룸스프레이", "탈취제", "차량용방향제")))));

	/** 대분류 value(영문 slug) — content_analyses.main_category CHECK와 동일 집합. */
	public static final Set<String> MAIN_CATEGORIES =
			TREE.stream().map(Main::value).collect(Collectors.toUnmodifiableSet());

	/** 유통사 상호명 — 프론트 유통사 필터값 고정. */
	public static final Set<String> DISTRIBUTORS = Set.of("올리브영", "다이소");

	private static final Set<String> MID_AND_SUB_LABELS = TREE.stream()
			.flatMap(m -> m.mids().stream())
			.flatMap(mid -> {
				Set<String> s = new LinkedHashSet<>();
				s.add(mid.label());
				s.addAll(mid.subs());
				return s.stream();
			})
			.collect(Collectors.toUnmodifiableSet());

	private static final Set<String> SUB_LABELS = TREE.stream()
			.flatMap(m -> m.mids().stream())
			.flatMap(mid -> mid.subs().stream())
			.collect(Collectors.toUnmodifiableSet());

	private BeautyTaxonomy() {
	}

	/** sub_categories 어휘 — 중분류+소분류 라벨 전체 (프론트가 배열 포함으로 매칭). */
	public static Set<String> allMidAndSubLabels() {
		return MID_AND_SUB_LABELS;
	}

	/** detected_product_categories 어휘 — 소분류 라벨만 (카드 칩). */
	public static Set<String> allSubLabels() {
		return SUB_LABELS;
	}

	/** VLM 프롬프트에 넣는 분류표 — slug(한글 라벨): 중분류[소분류…] 계층. */
	public static String promptTable() {
		return TREE.stream()
				.map(m -> "%s(%s): %s".formatted(m.value(), m.label(),
						m.mids().stream()
								.map(mid -> "%s[%s]".formatted(mid.label(), String.join(", ", mid.subs())))
								.collect(Collectors.joining(" · "))))
				.collect(Collectors.joining("\n"));
	}
}

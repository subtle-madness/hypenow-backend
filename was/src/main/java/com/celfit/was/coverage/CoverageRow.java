package com.celfit.was.coverage;

/** 커버리지 매트릭스 한 행 — 화면 요소 하나의 DB 채움 상태. grade는 상태 문구에서 유도한 표시 등급. */
public record CoverageRow(int ord, String element, String source, String filled, String status, String grade) {

	public static CoverageRow of(int ord, String element, String source, String filled, String status) {
		return new CoverageRow(ord, element, source, filled, status, gradeOf(status));
	}

	private static String gradeOf(String status) {
		if (status.startsWith("준비됨") || status.startsWith("정상 범위")) {
			return "ok";
		}
		if (status.startsWith("없음") || status.startsWith("누락")) {
			return "bad";
		}
		return "warn"; // 일부 누락 · 부분 · 옛 산출물 등
	}
}

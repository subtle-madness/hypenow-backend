package com.celfit.instagram.source.self;

import java.util.List;
import java.util.Map;

/**
 * 저수준 전송 응답 — 상태코드 + 본문(gunzip 완료) + 응답 헤더. 401은 전송이 복원해 넣는다(헤더 없음).
 * 헤더가 필요 없는 기존 호출부(대부분)는 2-인자 생성자를 그대로 쓴다.
 */
public record SelfResponse(int status, String body, Map<String, List<String>> headers) {

	public SelfResponse(int status, String body) {
		this(status, body, Map.of());
	}

	/** 헤더 이름 대소문자 무관 조회 — 없으면 빈 리스트(예: Set-Cookie에서 csrftoken 추출용). */
	public List<String> header(String name) {
		for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(name)) {
				return entry.getValue();
			}
		}
		return List.of();
	}
}

package com.celfit.was.v1.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/** 스펙 3.1 공통 envelope. meta는 목록 응답에만(null이면 직렬화 생략), data·error는 항상 노출. */
public record ApiResponse<T>(boolean success, T data, ApiError error,
		@JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> meta) {

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(true, data, null, null);
	}

	public static <T> ApiResponse<T> ok(T data, Map<String, Object> meta) {
		return new ApiResponse<>(true, data, null, meta);
	}

	public static ApiResponse<Void> fail(String code, String message) {
		return new ApiResponse<>(false, null, new ApiError(code, message), null);
	}
}

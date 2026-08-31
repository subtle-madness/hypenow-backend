package com.celfit.instagram.source;

/** 관측 대상 부재(계정 삭제·개명, 게시물 삭제 등 404) — 재시도 무의미. 호출자가 종결 처리한다. */
public class SubjectNotFoundException extends RuntimeException {

	public SubjectNotFoundException(String message) {
		super(message);
	}
}

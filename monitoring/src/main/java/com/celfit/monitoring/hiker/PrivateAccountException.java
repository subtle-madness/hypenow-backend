package com.celfit.monitoring.hiker;

/** 비공개 계정 — 게시물 열거가 불가능하다. 대상 상태를 별도로 표시하기 위해 404와 분리한다. */
public class PrivateAccountException extends RuntimeException {

	public PrivateAccountException(String message) {
		super(message);
	}
}

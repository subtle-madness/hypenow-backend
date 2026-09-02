package com.celfit.instagram.source;

/**
 * share 단축 링크 해소 불가 — URL 형식 불량 또는 Hiker 400(계약 §2-6 SHARE_LINK_UNRESOLVED).
 * 게시물 부재(404)는 SubjectNotFoundException으로 별도 구분한다.
 */
public class ShareLinkUnresolvedException extends RuntimeException {

	public ShareLinkUnresolvedException(String message) {
		super(message);
	}
}

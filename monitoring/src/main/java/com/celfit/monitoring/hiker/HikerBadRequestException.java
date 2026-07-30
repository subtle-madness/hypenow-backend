package com.celfit.monitoring.hiker;

/**
 * Hiker HTTP 400 — 요청 형식 불량. 다른 5xx·IO 실패(HikerFetchException)와 구분해야
 * share 해소 호출자가 이를 ShareLinkUnresolvedException으로 갈아 끼울 수 있다.
 * 구분 없이 쓰는 다른 호출자에게는 기존과 동일하게 HikerFetchException으로 처리된다(상속).
 */
public class HikerBadRequestException extends HikerFetchException {

	public HikerBadRequestException(String message) {
		super(message);
	}
}

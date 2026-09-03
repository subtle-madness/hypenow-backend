package com.celfit.monitoring.hiker;

import com.celfit.instagram.source.HikerHttp;
import com.celfit.monitoring.hiker.HikerConcurrencyLimiter.Lane;

/**
 * Hiker 전송 동시 in-flight 상한 데코레이터 — 실제 상한 로직은 {@link HikerConcurrencyLimiter}
 * (공유 빈)에 있고 이 클래스는 체인에 끼우는 얇은 껍데기다. 레인은 <b>체인이 어느 소비자용인가</b>
 * 로 정해진다({@code HikerConfig}: 배치 소비자용 체인은 BATCH, 사용자 대면 동기 체인은 SYNC) —
 * ThreadLocal 같은 암묵 전파 없이 배선으로 못박는 편이 추적 가능하다.
 *
 * <p>체인 위치는 <b>{@link TimedHikerHttp} 바로 바깥</b>이다(HikerConfig 조립 참조):
 * <ul>
 *   <li>안쪽(Timed·전송)만 퍼밋 구간에 두어 <b>퍼밋 보유 시간 = 네트워크 왕복</b>이 되게 한다 —
 *       바깥의 원형 적재·콜 집계(DB 쓰기)까지 퍼밋을 쥐고 있으면 상한이 실질 처리량을 깎는다.</li>
 *   <li>{@code external.call} 타이머(Timed)가 <b>대기 시간을 포함하지 않는다</b> — 벤더 지연과
 *       자체 큐잉이 한 지표에 섞이면 "계층별 p95 뺄셈" 진단이 무의미해진다. 대기·거절은 별도
 *       지표({@code hiker.concurrency.*})로 본다.</li>
 * </ul>
 */
public class ConcurrencyLimitedHikerHttp implements HikerHttp {

	private final HikerHttp delegate;
	private final HikerConcurrencyLimiter limiter;
	private final Lane lane;

	public ConcurrencyLimitedHikerHttp(HikerHttp delegate, HikerConcurrencyLimiter limiter, Lane lane) {
		this.delegate = delegate;
		this.limiter = limiter;
		this.lane = lane;
	}

	@Override
	public String get(String path) {
		return limiter.call(lane, () -> delegate.get(path));
	}
}

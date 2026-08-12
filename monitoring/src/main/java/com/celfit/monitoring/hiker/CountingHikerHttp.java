package com.celfit.monitoring.hiker;

import com.celfit.monitoring.store.BrandCallCountRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 브랜드 콜 집계 데코레이터(2026-08-12 어드민 크롤링 비용 설계) — 브랜드 컨텍스트
 * ({@link BrandCallContext}) 안에서 성공한 콜만 brand_call_count에 +1한다.
 *
 * <p>전송 계층에서 세는 이유는 {@link RecordingHikerHttp}와 같다: 열거·댓글은 페이지마다 콜이
 * 나가는데 파싱 계층에서 세면 페이지 수가 감사에서 사라진다. 여기서 감싸면 "HTTP 교환 1번 = 1콜"이
 * 구조적으로 성립한다(재시도는 {@link JdkHikerHttp} 내부라 논리 콜 1로 접힌다).
 *
 * <p>성공분만 세는 근거: Hiker 과금은 성공 응답 기준이고, 실패(재시도 소진·404)까지 세면 비용
 * 추정치가 부풀어 오차 방향이 나빠진다. 집계 실패는 삼킨다 — 비용 관측이 수집을 죽이면 안 된다.
 *
 * <p>스프링 빈이 아니다 — RecordingHikerHttp와 같은 이유({@code HikerHttp} 자기참조 배선 회피)로
 * {@code HikerConfig}가 명시 조립한다.
 */
public class CountingHikerHttp implements HikerHttp {

	private static final Logger log = LoggerFactory.getLogger(CountingHikerHttp.class);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final HikerHttp delegate;
	private final BrandCallContext context;
	private final BrandCallCountRepository counts;

	public CountingHikerHttp(HikerHttp delegate, BrandCallContext context, BrandCallCountRepository counts) {
		this.delegate = delegate;
		this.context = context;
		this.counts = counts;
	}

	@Override
	public String get(String path) {
		String body = delegate.get(path);   // 실패는 예외로 나가 집계되지 않는다(성공 콜만 과금 정합)
		count();
		return body;
	}

	private void count() {
		Long brandId = context.currentBrandId();
		if (brandId == null) {
			return;   // 브랜드 밖 콜(캠페인 모니터링 등) — 집계 대상 아님
		}
		try {
			counts.add(brandId, LocalDate.now(KST), 1);
		} catch (RuntimeException e) {
			log.warn("브랜드 콜 집계 실패(무시 — 수집은 계속) — brand {}: {}", brandId, e.toString());
		}
	}
}

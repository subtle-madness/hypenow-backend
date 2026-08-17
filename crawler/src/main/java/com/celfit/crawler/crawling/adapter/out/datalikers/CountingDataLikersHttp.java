package com.celfit.crawler.crawling.adapter.out.datalikers;

import com.celfit.crawler.crawling.application.port.out.PaidCallCounter;

/**
 * DataLikers용 과금 콜 집계 데코레이터 — {@code CountingHikerHttp}와 같은 역할·같은 근거다.
 * DataLikers는 {@code HikerHttp}를 지나지 않으므로 그쪽 데코레이터가 커버하지 못한다:
 * 전송이 둘이면 데코레이터도 둘이어야 "HTTP 교환 1번 = 1콜"이 전 유료 경로에서 성립한다.
 *
 * <p>실패는 예외로 빠져나가 세지 않는다. Hiker와 달리 DataLikers는 오류 응답의 과금 정책을
 * 공개하지 않아, 상태코드별로 갈라 셀 근거가 없다 — 확인되기 전까지는 과소 계상 쪽으로 둔다
 * (비용을 부풀리는 것보다 오차 방향이 낫다). 운영 물량도 4일치 5실행/88계정으로 미미하다.
 *
 * <p><b>단가는 다르다</b>: DataLikers는 요청당 $0.0006, HikerAPI는 $0.001이다(application.yml).
 * 그런데 {@code crawl_run.request_count}는 단가 없는 순수 요청 수이고, 이걸 읽는 어드민 전역
 * 비용 API는 어드민이 정하는 <b>전역 단가 하나</b>(app_setting {@code crawling.unit-price-usd})를
 * 전 소스에 곱한다 — 모니터링 몫과 크롤러 몫도 이미 그렇게 합산된다. 즉 공급자별 단가 차이는
 * 그 API가 의도적으로 접어 둔 근사이지 이 데코레이터가 만드는 오차가 아니다. 여기서 안 세면
 * 요청이 0으로 사라져(이 커밋이 고치는 바로 그 버그) 오차가 오히려 커진다. 공급자별 정산이
 * 필요해지면 집계 축(뷰가 job 단위라 공급자 구분이 없다)부터 손대야 한다.
 *
 * <p>스프링 빈이 아니다 — {@code DataLikersHttp} 자기참조 배선을 피하려고
 * {@code CrawlerConfig}가 {@link JdkDataLikersHttp}를 감싸 명시 조립한다.
 */
public class CountingDataLikersHttp implements DataLikersHttp {

    private final DataLikersHttp delegate;
    private final PaidCallCounter counter;

    public CountingDataLikersHttp(DataLikersHttp delegate, PaidCallCounter counter) {
        this.delegate = delegate;
        this.counter = counter;
    }

    @Override
    public String get(String path) {
        String body = delegate.get(path);
        counter.countOne();
        return body;
    }
}

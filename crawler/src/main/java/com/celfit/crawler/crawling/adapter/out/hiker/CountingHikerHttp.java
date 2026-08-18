package com.celfit.crawler.crawling.adapter.out.hiker;

import com.celfit.crawler.crawling.application.port.out.PaidCallCounter;

/**
 * 과금 콜 집계 데코레이터 — HikerAPI 응답을 정상 수신한 요청만 {@link PaidCallCounter}에 +1한다.
 *
 * <p>전송 계층에서 세는 이유: 해시태그 열거처럼 페이지마다 콜이 나가는 경로를 파싱·잡 계층에서
 * 세면 실패로 끊긴 실행의 페이지 수가 집계에서 사라진다. 여기서 감싸면 "HTTP 교환 1번 = 1콜"이
 * 구조적으로 성립해 성공·실패 경로가 같은 규칙을 따른다(monitoring CountingHikerHttp와 동일 근거).
 *
 * <p><b>성공분만 세는 근거</b>: Hiker 과금은 성공 응답 기준이다. 타임아웃·5xx·404는 예외로
 * 빠져나가므로 세지 않는다 — "요청은 보냈으나 응답을 못 받은 것"까지 세면 비용이 부풀어
 * 오차 방향이 반대로 나빠진다.
 *
 * <p>단, 잡이 스스로 '요청은 이미 샀다'고 판단해 성공으로 접는 soft-404 경로(ReelsJob의 '릴스 없음',
 * SimilarJob의 'chaining 불가')는 여전히 {@code ApifyResult.requestCount}로 자기 몫을 보고한다 —
 * 성공 경로의 기존 규칙은 이 데코레이터가 바꾸지 않는다({@code CrawlExecutor} 주석 참조).
 *
 * <p>스프링 빈이 아니다 — {@code HikerHttp} 자기참조 배선을 피하려고 {@code CrawlerConfig}가
 * {@link JdkHikerHttp}를 감싸 명시 조립한다.
 */
public class CountingHikerHttp implements HikerHttp {

    private final HikerHttp delegate;
    private final PaidCallCounter counter;

    public CountingHikerHttp(HikerHttp delegate, PaidCallCounter counter) {
        this.delegate = delegate;
        this.counter = counter;
    }

    @Override
    public String get(String path) {
        String body = delegate.get(path);   // 실패는 예외로 나가 집계되지 않는다(성공 콜만 과금 정합)
        counter.countOne();
        return body;
    }
}

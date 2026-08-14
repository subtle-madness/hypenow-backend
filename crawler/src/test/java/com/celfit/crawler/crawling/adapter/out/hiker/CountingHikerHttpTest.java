package com.celfit.crawler.crawling.adapter.out.hiker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.NotFoundException;
import com.celfit.crawler.crawling.application.port.out.PaidCallCounter;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CountingHikerHttpTest {

    private final PaidCallCounter counter = new PaidCallCounter();

    @Test void 성공_응답마다_과금_콜을_센다() {
        var http = new CountingHikerHttp(path -> "{}", counter);
        var paid = new AtomicInteger();

        counter.scoped(paid, () -> {
            http.get("/a");
            http.get("/b");
            return null;
        });

        assertThat(paid.get()).isEqualTo(2);
    }

    @Test void 응답을_못_받은_요청은_세지_않는다() {
        // Hiker 과금은 성공 응답 기준 — 타임아웃·5xx까지 세면 비용이 부풀어 오차 방향이 반대로 나빠진다
        var http = new CountingHikerHttp(path -> { throw new ApifyException("Hiker HTTP 500"); }, counter);
        var paid = new AtomicInteger();

        assertThatThrownBy(() -> counter.scoped(paid, () -> http.get("/a")))
                .isInstanceOf(ApifyException.class);

        assertThat(paid.get()).isZero();
    }

    @Test void 대상_부재_404도_예외로_나가므로_세지_않는다() {
        var http = new CountingHikerHttp(path -> { throw new NotFoundException("Hiker HTTP 404"); }, counter);
        var paid = new AtomicInteger();

        assertThatThrownBy(() -> counter.scoped(paid, () -> http.get("/a")))
                .isInstanceOf(NotFoundException.class);

        assertThat(paid.get()).isZero();
    }

    @Test void 스코프_밖_호출은_아무데도_세지_않는다() {
        var http = new CountingHikerHttp(path -> "{}", counter);

        assertThat(http.get("/a")).isEqualTo("{}");   // 예외 없이 통과하면 충분
    }

    @Test void 스코프는_중첩되면_안쪽_실행_몫만_센다() {
        var http = new CountingHikerHttp(path -> "{}", counter);
        var outer = new AtomicInteger();
        var inner = new AtomicInteger();

        counter.scoped(outer, () -> {
            http.get("/outer-1");
            counter.scoped(inner, () -> http.get("/inner-1"));
            http.get("/outer-2");
            return null;
        });

        assertThat(inner.get()).isEqualTo(1);
        assertThat(outer.get()).isEqualTo(2);   // 안쪽 스코프 종료 후 바깥으로 복원됐다
    }
}

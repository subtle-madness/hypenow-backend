package com.celfit.crawler.crawling.application.port.out;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * "실행 1건이 지금까지 산 유료 요청 수"를 드는 스레드 로컬 카운터 — {@code CrawlExecutor}가
 * 실행을 감쌀 때 스코프를 열고, 전송 데코레이터({@code CountingHikerHttp})가 성공 응답마다
 * {@link #countOne()}으로 +1한다.
 *
 * <p>존재 이유는 <b>실패 경로의 요청 수 유실</b>이다. 과금 카운트를 {@link ApifyResult}로만
 * 나르면 결과를 못 만들고 던지는 실행(페이지 4개를 사고 5번째에서 실패 등)은 이미 산 요청을
 * 통째로 잃는다. 예외에 카운트를 싣는 안·페처가 부분 결과를 돌려주는 안도 있었지만, 둘 다
 * "새 유료 경로를 짤 때마다 저자가 잊지 않아야" 성립한다 — 유실이 조용히 재발하는 구조라
 * 애초의 버그와 실패 양상이 같다. 전송 계층에서 세면 돈이 나가는 그 지점이 유일한 산지라
 * 성공·실패 경로가 자동으로 같은 규칙을 따른다.
 *
 * <p>monitoring 모듈이 같은 비용 API의 다른 갈래를 이미 이 방식으로 센다
 * (CountingHikerHttp + BrandCallContext, 2026-08-12 설계 §2) — 정의를 맞춰 둔다.
 *
 * <p><b>전파는 명시적이다</b>: ThreadLocal은 스레드 경계를 넘지 못한다. 워커 풀로 팬아웃하는
 * 수집기(HikerMobileProfileFetcher 등)의 콜은 이 카운터에 잡히지 않으므로, 그런 경로가
 * 실패 경로의 카운트를 필요로 하게 되면 태스크 제출 쪽이 sink를 직접 넘겨야 한다. 현재는
 * 팬아웃 수집기가 계정 단위 예외를 전부 삼켜 실행 자체가 실패로 끝나지 않아 문제가 없다.
 */
@Component
public class PaidCallCounter {

    private final ThreadLocal<AtomicInteger> current = new ThreadLocal<>();

    /**
     * sink를 현재 스레드에 걸고 body를 실행한다. body가 던져도 sink에는 그때까지 성공한
     * 콜 수가 남는다 — 실패 실행의 과금분을 읽어내는 지점이 바로 여기다.
     */
    public <T> T scoped(AtomicInteger sink, Supplier<T> body) {
        AtomicInteger prev = current.get();
        current.set(sink);
        try {
            return body.get();
        } finally {
            // 중첩 실행(이론상)에서도 바깥 스코프로 복원 — 최상위면 remove로 누수 차단.
            if (prev == null) {
                current.remove();
            } else {
                current.set(prev);
            }
        }
    }

    /** 과금된 콜 1건. 스코프 밖 호출(어드민 단발 조회 등)은 집계 대상이 아니라 no-op. */
    public void countOne() {
        AtomicInteger sink = current.get();
        if (sink != null) {
            sink.incrementAndGet();
        }
    }
}

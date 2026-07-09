package com.celfit.crawler.crawling.application.port.out;

import java.util.Map;

/** 액터 실행 추상화 — 잡은 이 인터페이스만 의존한다(테스트는 fake). */
public interface ApifyRunnerPort {
    ApifyResult run(String actorId, Map<String, Object> input);
}

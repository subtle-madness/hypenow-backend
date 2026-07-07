package com.celfit.crawler.apify;

import java.util.Map;

/** 액터 실행 추상화 — 잡은 이 인터페이스만 의존한다(테스트는 fake). */
public interface ApifyRunner {
    ApifyResult run(String actorId, Map<String, Object> input);
}

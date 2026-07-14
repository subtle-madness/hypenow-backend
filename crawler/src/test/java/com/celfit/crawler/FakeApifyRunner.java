package com.celfit.crawler;

import com.celfit.crawler.crawling.application.port.out.ApifyException;
import com.celfit.crawler.crawling.application.port.out.ApifyResult;
import com.celfit.crawler.crawling.application.port.out.ApifyRunnerPort;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** 잡 테스트용 fake. 스크립트된 결과를 순서대로 반환하고 (actorId, input)을 기록. */
public class FakeApifyRunner implements ApifyRunnerPort {

    public record Call(String actorId, Map<String, Object> input) {}

    private final Deque<Object> script = new ArrayDeque<>();  // ApifyResult 또는 ApifyException
    public final List<Call> calls = new ArrayList<>();

    public void enqueue(List<Map<String, Object>> items) {
        script.add(new ApifyResult("fake-run-" + (script.size() + 1), items));
    }

    public void enqueueFailure(String message) {
        script.add(new ApifyException(message));
    }

    /** 테스트 간 상태 격리 — fake는 컨텍스트 싱글턴이라 각 테스트 @BeforeEach에서 호출할 것. */
    public void reset() {
        script.clear();
        calls.clear();
    }

    @Override
    public ApifyResult run(String actorId, Map<String, Object> input) {
        calls.add(new Call(actorId, input));
        if (script.isEmpty()) {
            throw new IllegalStateException("스크립트되지 않은 액터 호출: " + actorId);
        }
        Object next = script.pop();
        if (next instanceof ApifyException e) throw e;
        return (ApifyResult) next;
    }
}

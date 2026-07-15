package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.domain.JobName;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 실행 중 잡의 진행률(처리/전체)을 인메모리로 추적 — 대시보드 실시간 표시용.
 * 단일 인스턴스 전제(JobLock과 동일). 잡 시작 시 start, 청크 끝날 때 advance, 완료/실패 시 finish.
 */
@Component
public class JobProgress {

    public record View(int current, int total) {
        public int percent() {
            return total <= 0 ? 0 : Math.min(100, (int) Math.round(current * 100.0 / total));
        }
    }

    private final Map<JobName, View> progress = new ConcurrentHashMap<>();

    public void start(JobName job, int total) {
        progress.put(job, new View(0, total));
    }

    public void advance(JobName job, int delta) {
        progress.compute(job, (k, v) ->
                v == null ? new View(delta, delta) : new View(v.current() + delta, v.total()));
    }

    /** 완료·실패 어느 경로든 호출해 진행 상태를 지운다(finally 권장). */
    public void finish(JobName job) {
        progress.remove(job);
    }

    /** 진행 중이 아니면 null. */
    public View get(JobName job) {
        return progress.get(job);
    }
}

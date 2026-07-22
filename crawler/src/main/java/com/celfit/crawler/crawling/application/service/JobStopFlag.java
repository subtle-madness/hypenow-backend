package com.celfit.crawler.crawling.application.service;

import com.celfit.crawler.crawling.domain.JobName;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * 잡 협조적 중지 플래그 (단일 인스턴스 전제 — JobLock과 동일한 인프로세스 방식).
 * 중지는 강제 인터럽트가 아니라 협조적이다 — 각 잡이 단위 작업(키워드·청크·방문·시드) 경계에서
 * 플래그를 확인하고 조기 종료한다. 진행 중이던 단위 작업은 끝까지 수행·커밋된다(부분 저장 보존).
 */
@Component
public class JobStopFlag {

    private final ConcurrentHashMap<JobName, AtomicBoolean> flags = new ConcurrentHashMap<>();

    public void request(JobName job) {
        flags.computeIfAbsent(job, k -> new AtomicBoolean(false)).set(true);
    }

    public boolean isRequested(JobName job) {
        AtomicBoolean f = flags.get(job);
        return f != null && f.get();
    }

    /** 잡 시작 시 호출 — 직전 실행 말미의 늦은 중지 요청이 다음 실행을 죽이지 않게 한다. */
    public void clear(JobName job) {
        AtomicBoolean f = flags.get(job);
        if (f != null) f.set(false);
    }
}

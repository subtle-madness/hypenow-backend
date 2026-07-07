package com.celfit.crawler.job;

import com.celfit.crawler.domain.JobName;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** 같은 잡 동시 실행 방지 (단일 인스턴스 전제 — 인프로세스 락). */
@Component
public class JobLock {

    private final ConcurrentHashMap<JobName, AtomicBoolean> locks = new ConcurrentHashMap<>();

    public boolean tryAcquire(JobName job) {
        return locks.computeIfAbsent(job, k -> new AtomicBoolean(false)).compareAndSet(false, true);
    }

    public void release(JobName job) {
        AtomicBoolean l = locks.get(job);
        if (l != null) l.set(false);
    }

    public boolean isRunning(JobName job) {
        AtomicBoolean l = locks.get(job);
        return l != null && l.get();
    }
}

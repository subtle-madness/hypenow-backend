package com.celfit.crawler.dashboard.application;

import com.celfit.crawler.content.domain.ContentStatus;
import com.celfit.crawler.content.application.port.out.ContentRepository;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 상태 요약 — 인플루언서 중심 재구현은 Task 10에서 진행. 현재는 빈 셸. */
@Service
public class StatusService {

    public record StatusSummary(Map<ContentStatus, Long> contentByStatus) {}

    private final ContentRepository contents;

    public StatusService(ContentRepository contents) {
        this.contents = contents;
    }

    public StatusSummary summary() {
        Map<ContentStatus, Long> byStatus = new EnumMap<>(ContentStatus.class);
        for (ContentStatus s : ContentStatus.values()) {
            byStatus.put(s, contents.countByStatus(s));
        }
        return new StatusSummary(byStatus);
    }
}

package com.celfit.crawler.dashboard.adapter.in.web;

import com.celfit.crawler.common.log.LogBuffer;

import com.celfit.crawler.dashboard.application.StatusService;
import com.celfit.crawler.crawling.domain.*;
import com.celfit.crawler.content.domain.*;
import com.celfit.crawler.settings.domain.*;
import com.celfit.crawler.crawling.application.port.out.*;
import com.celfit.crawler.content.application.port.out.*;
import com.celfit.crawler.settings.application.port.out.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Controller
public class UiController {

    private final StatusService statusService;
    private final CrawlRunRepository runs;
    private final CategoryRepository categories;
    private final ContentRepository contents;
    private final AccountRepository accounts;
    private final RawPostDetailRepository rawDetails;
    private final RawCommentRepository rawComments;
    private final RawProfileRepository rawProfiles;
    private final RawDiscoveryPostRepository rawDiscovery;
    private final ObjectMapper objectMapper;
    private final LogBuffer logBuffer;

    private final com.celfit.crawler.crawling.application.service.JobLock jobLock;
    private final com.celfit.crawler.crawling.application.service.JobProgress jobProgress;

    public UiController(StatusService statusService, CrawlRunRepository runs,
                        CategoryRepository categories, ContentRepository contents,
                        AccountRepository accounts, RawPostDetailRepository rawDetails,
                        RawCommentRepository rawComments, RawProfileRepository rawProfiles,
                        RawDiscoveryPostRepository rawDiscovery,
                        ObjectMapper objectMapper, LogBuffer logBuffer,
                        com.celfit.crawler.crawling.application.service.JobLock jobLock,
                        com.celfit.crawler.crawling.application.service.JobProgress jobProgress) {
        this.statusService = statusService;
        this.runs = runs;
        this.categories = categories;
        this.contents = contents;
        this.accounts = accounts;
        this.rawDetails = rawDetails;
        this.rawComments = rawComments;
        this.rawProfiles = rawProfiles;
        this.rawDiscovery = rawDiscovery;
        this.objectMapper = objectMapper;
        this.logBuffer = logBuffer;
        this.jobLock = jobLock;
        this.jobProgress = jobProgress;
    }

    /** 현재 작업 바(실시간)용 한 잡의 상태. */
    public record JobStatusRow(String label, boolean running, int current, int total, int percent) {}

    @GetMapping("/")
    public String root() {
        return "redirect:/ui";
    }

    /** 대시보드 상태 카드용 뷰. key는 badge 색상 클래스 겸 라벨. */
    public record StatusTile(String key, long count, String desc) {}

    @GetMapping("/ui")
    public String dashboard(Model model) {
        model.addAttribute("summary", statusService.summary());
        return "dashboard";
    }

    private static long n(java.util.Map<ContentStatus, Long> by, ContentStatus s) {
        return by.getOrDefault(s, 0L);
    }

    @GetMapping("/ui/fragments/runs")
    public String runsFragment(Model model) {
        var runList = runs.findTop50ByOrderByIdDesc();
        model.addAttribute("runs", runList);
        // DISCOVER run별 "중복 재발굴"(이미 발굴됐던 게시물) 수 — 신규 PENDING이 왜 안 늘었는지 표시
        var discoverIds = runList.stream()
                .filter(r -> r.getJob() == JobName.DISCOVER)
                .map(CrawlRun::getId)
                .toList();
        java.util.Map<Long, Long> dupByRun = new java.util.HashMap<>();
        if (!discoverIds.isEmpty()) {
            for (var s : rawDiscovery.discoveryStats(discoverIds)) {
                dupByRun.put(s.getRunId(), s.getDuplicates());
            }
        }
        model.addAttribute("dupByRun", dupByRun);
        return "fragments/runs :: table";
    }

    /** 현재 작업 바(실시간): 각 잡의 실행 여부 + 진행률. */
    @GetMapping("/ui/fragments/status")
    public String statusFragment(Model model) {
        model.addAttribute("jobs", java.util.List.of(
                jobStatus(JobName.DISCOVER, "발굴"),
                jobStatus(JobName.QUALIFY, "판정"),
                jobStatus(JobName.AGGREGATE, "집계")));
        return "fragments/status :: bar";
    }

    private JobStatusRow jobStatus(JobName job, String label) {
        boolean running = jobLock.isRunning(job);
        var p = jobProgress.get(job);
        return new JobStatusRow(label, running,
                p == null ? 0 : p.current(), p == null ? 0 : p.total(), p == null ? 0 : p.percent());
    }

    /** 상태 카드 실시간 갱신용 — 파이프라인/제외 그룹 타일을 프래그먼트로 반환. */
    @GetMapping("/ui/fragments/status-tiles")
    public String statusTilesFragment(Model model) {
        StatusService.StatusSummary s = statusService.summary();
        java.util.Map<ContentStatus, Long> by = s.contentByStatus();
        model.addAttribute("summary", s);
        // 정상 수집 흐름: 발견 → 채택 → 집계
        model.addAttribute("pipelineTiles", java.util.List.of(
                new StatusTile("PENDING", n(by, ContentStatus.PENDING), "발견됨 · 프로필 판정 전"),
                new StatusTile("QUALIFIED", n(by, ContentStatus.QUALIFIED), "규칙 통과 · 집계 대상"),
                new StatusTile("AGGREGATED", n(by, ContentStatus.AGGREGATED), "좋아요·댓글 집계 완료")));
        // 흐름에서 빠진 것들
        model.addAttribute("droppedTiles", java.util.List.of(
                new StatusTile("EXCLUDED", n(by, ContentStatus.EXCLUDED), "규칙 탈락 · 팔로워/유형 미달"),
                new StatusTile("GONE", n(by, ContentStatus.GONE), "삭제·비공개로 사라짐"),
                new StatusTile("FAILED", n(by, ContentStatus.FAILED), "집계 재시도 초과 · 포기")));
        return "fragments/status-tiles :: tiles";
    }

    @GetMapping("/ui/fragments/logs")
    public String logsFragment(Model model) {
        model.addAttribute("lines", logBuffer.lines());
        return "fragments/logs :: panel";
    }

    @GetMapping("/ui/jobs")
    public String jobs(Model model) {
        model.addAttribute("categories", categories.findByEnabledTrue());
        return "jobs";
    }

    @GetMapping("/ui/contents")
    public String contents(@RequestParam(required = false) java.util.List<ContentStatus> status,
                           @RequestParam(defaultValue = "0") int page, Model model) {
        page = Math.max(page, 0);
        var pageable = PageRequest.of(page, 50, Sort.by(Sort.Direction.DESC, "id"));
        var result = (status == null || status.isEmpty()) ? contents.findAll(pageable)
                                                          : contents.findByStatusIn(status, pageable);
        model.addAttribute("page", result);
        model.addAttribute("status", status);
        model.addAttribute("statuses", ContentStatus.values());
        return "contents";
    }

    @GetMapping("/ui/contents/{id}")
    public String contentDetail(@PathVariable Long id, Model model) {
        Content content = contents.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "콘텐츠 없음"));
        var detail = rawDetails.findTopByContentIdOrderByCapturedAtDesc(id);
        model.addAttribute("content", content);
        model.addAttribute("detailJson", detail.map(d -> pretty(d.getPayload())).orElse(null));
        model.addAttribute("taggedUsers", detail.map(d -> taggedUsers(d.getPayload()))
                .orElse(java.util.List.of()));
        model.addAttribute("comments", rawComments.findTop100ByContentIdOrderByIdDesc(id));
        model.addAttribute("profileJson", accounts.findByUsername(content.getOwnerUsername())
                .flatMap(a -> rawProfiles.findTopByAccountIdOrderByCapturedAtDesc(a.getId()))
                .map(p -> pretty(p.getPayload())).orElse(null));
        return "content-detail";
    }

    /** 상세 payload의 taggedUsers → "이름 @username" 표시 문자열. 브랜드 태그 확인용. */
    private java.util.List<String> taggedUsers(java.util.Map<String, Object> payload) {
        if (!(payload.get("taggedUsers") instanceof java.util.List<?> tags)) return java.util.List.of();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Object t : tags) {
            if (!(t instanceof java.util.Map<?, ?> m)) continue;
            String username = m.get("username") instanceof String s ? s : null;
            String fullName = m.get("full_name") instanceof String s ? s : null;
            if (username == null && fullName == null) continue;
            out.add(((fullName != null ? fullName : "") + (username != null ? " @" + username : "")).trim());
        }
        return out;
    }

    private String pretty(Object payload) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            return String.valueOf(payload);
        }
    }
}

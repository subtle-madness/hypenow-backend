package com.celfit.crawler.dashboard.adapter.in.web;

import com.celfit.crawler.common.log.LogBuffer;

import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentStatus;
import com.celfit.crawler.crawling.adapter.out.hiker.HikerProperties;
import com.celfit.crawler.crawling.application.port.out.*;
import com.celfit.crawler.crawling.domain.CrawlRun;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import com.celfit.crawler.crawling.domain.JobName;
import com.celfit.crawler.crawling.domain.RawComment;
import com.celfit.crawler.dashboard.application.JobCostEstimator;
import com.celfit.crawler.dashboard.application.StatusService;
import com.celfit.crawler.settings.application.service.SettingsService;
import com.celfit.crawler.crawling.application.port.out.*;
import com.celfit.crawler.crawling.application.service.JobLock;
import com.celfit.crawler.crawling.application.service.JobProgress;
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
    private final ContentRepository contents;
    private final RawPostDetailRepository rawDetails;
    private final RawCommentRepository rawComments;
    private final RawProfileRepository rawProfiles;
    private final RawDiscoveryPostRepository rawDiscovery;
    private final ObjectMapper objectMapper;
    private final LogBuffer logBuffer;

    private final JobLock jobLock;
    private final JobProgress jobProgress;
    private final JobCostEstimator jobCostEstimator;
    private final HikerProperties hikerProperties;
    private final SettingsService settings;

    public UiController(StatusService statusService, CrawlRunRepository runs,
                        ContentRepository contents, RawPostDetailRepository rawDetails,
                        RawCommentRepository rawComments, RawProfileRepository rawProfiles,
                        RawDiscoveryPostRepository rawDiscovery,
                        ObjectMapper objectMapper, LogBuffer logBuffer,
                        JobLock jobLock,
                        JobProgress jobProgress,
                        JobCostEstimator jobCostEstimator, HikerProperties hikerProperties,
                        SettingsService settings) {
        this.statusService = statusService;
        this.runs = runs;
        this.contents = contents;
        this.rawDetails = rawDetails;
        this.rawComments = rawComments;
        this.rawProfiles = rawProfiles;
        this.rawDiscovery = rawDiscovery;
        this.objectMapper = objectMapper;
        this.logBuffer = logBuffer;
        this.jobLock = jobLock;
        this.jobProgress = jobProgress;
        this.jobCostEstimator = jobCostEstimator;
        this.hikerProperties = hikerProperties;
        this.settings = settings;
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

    private static <K> long n(java.util.Map<K, Long> by, K key) {
        return by.getOrDefault(key, 0L);
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
        model.addAttribute("hikerCostPerRequest", hikerProperties.costPerRequestUsd());
        return "fragments/runs :: table";
    }

    /** 현재 작업 바(실시간): 각 잡의 실행 여부 + 진행률. */
    @GetMapping("/ui/fragments/status")
    public String statusFragment(Model model) {
        model.addAttribute("jobs", java.util.List.of(
                jobStatus(JobName.DISCOVER, "발굴"),
                jobStatus(JobName.QUALIFY, "판정"),
                jobStatus(JobName.COLLECT, "수집")));
        return "fragments/status :: bar";
    }

    private JobStatusRow jobStatus(JobName job, String label) {
        boolean running = jobLock.isRunning(job);
        var p = jobProgress.get(job);
        return new JobStatusRow(label, running,
                p == null ? 0 : p.current(), p == null ? 0 : p.total(), p == null ? 0 : p.percent());
    }

    /** 상태 카드 실시간 갱신용 — 인플루언서 판정 타일 + 게시물 수집 타일을 프래그먼트로 반환. */
    @GetMapping("/ui/fragments/status-tiles")
    public String statusTilesFragment(Model model) {
        StatusService.StatusSummary s = statusService.summary();
        java.util.Map<InfluencerStatus, Long> byInfluencer = s.influencerByStatus();
        java.util.Map<ContentStatus, Long> byContent = s.contentByStatus();
        model.addAttribute("summary", s);
        // 인플루언서 파이프라인: 발굴 → 판정 → (제외 또는 수집 대상)
        model.addAttribute("influencerTiles", java.util.List.of(
                new StatusTile("DISCOVERED", n(byInfluencer, InfluencerStatus.DISCOVERED), "발굴됨 · 판정 전"),
                new StatusTile("QUALIFIED", n(byInfluencer, InfluencerStatus.QUALIFIED), "판정 통과 · 수집 대상"),
                new StatusTile("EXCLUDED", n(byInfluencer, InfluencerStatus.EXCLUDED), "판정 탈락 · 제외"),
                new StatusTile("BACKFILL", s.backfillPending(), "판정 통과 · 첫 수집(백필) 대기"),
                new StatusTile("TRACK", s.trackDue(), "수집 완료 · 재방문 주기 도래")));
        // 게시물 수집 상태: collect 열거(QUALIFIED 인플루언서의 6개월 열거) 산출물만 대상 — 발굴
        // 부산물(discover 원시 게시물)은 수집 대상이 아니라 여기 집계에서 빠진다.
        model.addAttribute("contentTiles", java.util.List.of(
                new StatusTile("PENDING", n(byContent, ContentStatus.PENDING), "열거됨 · 댓글 수집 전"),
                new StatusTile("COLLECTED", n(byContent, ContentStatus.COLLECTED), "댓글까지 수집 완료"),
                new StatusTile("FAILED", n(byContent, ContentStatus.FAILED), "댓글 수집 재시도 초과 · 포기")));
        model.addAttribute("discoveryArchiveCount", s.discoveryArchiveCount());
        // 수집 범위는 설정값 — 하드코딩 문구 대신 현재 값 표시 (설정 화면에서 무중단 변경)
        model.addAttribute("backfillMonths", settings.backfillMonths());
        model.addAttribute("trackWindowDays", settings.trackWindowDays());
        model.addAttribute("revisitIntervalDays", settings.revisitIntervalDays());
        return "fragments/status-tiles :: tiles";
    }

    @GetMapping("/ui/fragments/logs")
    public String logsFragment(Model model) {
        model.addAttribute("lines", logBuffer.lines());
        return "fragments/logs :: panel";
    }

    @GetMapping("/ui/jobs")
    public String jobs(Model model) {
        model.addAttribute("costs", jobCostEstimator.estimates());
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

    /**
     * 열람용 댓글 1행. 구 파이프라인(raw_post_detail 기반 댓글)은 writer/text/writtenAt 실컬럼이
     * 채워지지만, 새 파이프라인(SELF_GQL 페이지 원형 수집)은 댓글 단위가 아니라 페이지 단위로
     * 저장되어 이 실컬럼이 설계상 NULL이다 — 그 경우 payload 원형을 pretty JSON으로 보여준다.
     */
    public record CommentRow(String writer, String text, String writtenAt, String rawJson) {}

    @GetMapping("/ui/contents/{id}")
    public String contentDetail(@PathVariable Long id, Model model) {
        Content content = contents.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "콘텐츠 없음"));
        var detail = rawDetails.findTopByContentIdOrderByCapturedAtDesc(id);
        model.addAttribute("content", content);
        model.addAttribute("detailJson", detail.map(d -> pretty(d.getPayload())).orElse(null));
        model.addAttribute("taggedUsers", detail.map(d -> taggedUsers(d.getPayload()))
                .orElse(java.util.List.of()));
        model.addAttribute("comments", commentRows(rawComments.findTop100ByContentIdOrderByIdDesc(id)));
        model.addAttribute("profileJson", rawProfiles
                .findTopByInfluencerIdOrderByCapturedAtDesc(content.getInfluencerId())
                .map(p -> pretty(p.getPayload())).orElse(null));
        return "content-detail";
    }

    private java.util.List<CommentRow> commentRows(java.util.List<RawComment> rows) {
        return rows.stream()
                .map(c -> c.getWriter() != null
                        ? new CommentRow(c.getWriter(), c.getText(), c.getWrittenAt(), null)
                        : new CommentRow(null, null, null, pretty(c.getPayload())))
                .toList();
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

package com.celfit.crawler.dashboard.adapter.in.web;

import com.celfit.crawler.common.log.LogBuffer;

import com.celfit.crawler.content.application.port.out.ContentRepository;
import com.celfit.crawler.content.domain.Content;
import com.celfit.crawler.content.domain.ContentStatus;
import com.celfit.crawler.crawling.adapter.out.hiker.HikerProperties;
import com.celfit.crawler.crawling.application.port.out.*;
import com.celfit.crawler.crawling.domain.BeautyClass;
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
    private final InfluencerRepository influencers;
    private final InfluencerDiscoveryRepository influencerDiscoveries;
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
                        InfluencerRepository influencers,
                        InfluencerDiscoveryRepository influencerDiscoveries,
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
        this.influencers = influencers;
        this.influencerDiscoveries = influencerDiscoveries;
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

    /** 파이프라인 단계별 타일 묶음 — 한 잡이 만든 상태들을 시각적으로 구분해 렌더. */
    public record StatusTileGroup(String title, java.util.List<StatusTile> tiles) {}

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
        // DISCOVER run별 인플루언서 기준 통계 — 건수(발굴 인플루언서 수)와 중복(이미 발굴됐던 인플루언서 수).
        // 발굴의 산출물은 게시물이 아니라 인플루언서이므로 게시물 수(itemCount)는 툴팁으로만 남긴다.
        var discoverIds = runList.stream()
                .filter(r -> r.getJob() == JobName.DISCOVER)
                .map(CrawlRun::getId)
                .toList();
        java.util.Map<Long, RawDiscoveryPostRepository.RunDiscoveryStat> infByRun = new java.util.HashMap<>();
        if (!discoverIds.isEmpty()) {
            for (var s : rawDiscovery.discoveryStats(discoverIds)) {
                infByRun.put(s.getRunId(), s);
            }
        }
        model.addAttribute("infByRun", infByRun);
        model.addAttribute("hikerCostPerRequest", hikerProperties.costPerRequestUsd());
        return "fragments/runs :: table";
    }

    /** 현재 작업 바(실시간): 각 잡의 실행 여부 + 진행률. */
    @GetMapping("/ui/fragments/status")
    public String statusFragment(Model model) {
        model.addAttribute("jobs", java.util.List.of(
                jobStatus(JobName.DISCOVER, "발굴"),
                jobStatus(JobName.QUALIFY, "판정"),
                jobStatus(JobName.BEAUTY, "뷰티판정"),
                jobStatus(JobName.SIMILAR, "유사발굴"),
                jobStatus(JobName.COLLECT, "프로필수집"),
                jobStatus(JobName.REELS, "릴스수집")));
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
        model.addAttribute("summary", s);
        // 인플루언서 파이프라인 — 같은 잡이 만드는 상태끼리 묶어 단계를 시각 구분:
        // discover가 DISCOVERED를 만들고, qualify가 QUALIFIED/EXCLUDED로 가르고,
        // beauty가 QUALIFIED를 뷰티/비뷰티로 가르고, collect는 뷰티 계정만
        // 방문 대기(미방문 또는 재방문 주기 도래) 순서대로 방문한다.
        // 모든 방문이 동일(최근 게시물 1회 수집)하므로 첫 방문/재방문을 구분하지 않는다.
        model.addAttribute("influencerGroups", java.util.List.of(
                new StatusTileGroup("① 발굴 — discover", java.util.List.of(
                        new StatusTile("DISCOVERED", n(byInfluencer, InfluencerStatus.DISCOVERED),
                                "발굴됨 · 판정 전"))),
                new StatusTileGroup("② 판정 — qualify가 가른 결과", java.util.List.of(
                        new StatusTile("QUALIFIED", n(byInfluencer, InfluencerStatus.QUALIFIED),
                                "판정 통과 · 뷰티 판정 대상"),
                        new StatusTile("EXCLUDED", n(byInfluencer, InfluencerStatus.EXCLUDED),
                                "판정 탈락 · 제외"))),
                new StatusTileGroup("③ 뷰티 판정 — beauty가 가른 결과 (QUALIFIED 내)", java.util.List.of(
                        new StatusTile("BEAUTY", s.beautyInfluencer(),
                                "뷰티 인플루언서 · 수집·유사발굴 대상"),
                        new StatusTile("BEAUTY_COMPANY", s.beautyCompany(),
                                "뷰티 회사 · 리스트업 전용(수집 제외)"),
                        new StatusTile("NOT_BEAUTY", s.beautyFalse(),
                                "비뷰티 · 수집 제외"),
                        new StatusTile("UNJUDGED", s.beautyUnjudged(),
                                "미판정 · 뷰티판정 대기"))),
                new StatusTileGroup("④ 수집 대기열 — 게시물을 위한 프로필 수집(collect)·릴스 수집(reels)이 방문할 대상",
                        java.util.List.of(
                        new StatusTile("READY", s.backfillPending() + s.trackDue(),
                                "게시물을 위한 프로필 수집 대기 · 뷰티 계정만"),
                        new StatusTile("REELS_READY", s.reelsDue(),
                                "릴스 수집 대기 · 뷰티 계정만")))));
        // 게시물 수집: collect 방문(프로필 내장 최근 피드 + 릴스 1페이지) 산출물만 대상 —
        // 발굴 부산물(discover 원시 게시물)은 수집 대상이 아니라 여기 집계에서 빠진다.
        // 댓글 수집은 꺼져 있으므로 상태 전이 대신 총계·유형별로 보여준다.
        // 계정 기준은 "수집을 수행한 계정 수" — 콘텐츠 보유 계정 수로 세면 피드가 0건인
        // 릴스 전용 계정이 빠져 보여 수집 진행도와 헷갈린다.
        model.addAttribute("contentTiles", java.util.List.of(
                new StatusTile("게시물", s.enumeratedTotal(),
                        "방문에서 수집된 전체 (원형 저장) · 수집 계정 " + s.anyCollectedAccounts() + "명"),
                new StatusTile("FEED", s.enumeratedFeed(),
                        "피드 — 프로필 내장 최근 12개에서 · 프로필 스냅샷 " + s.profileSnapshotAccounts() + "명"),
                new StatusTile("REELS", s.enumeratedReels(),
                        "릴스 — /v2/user/clips 1페이지에서 · 릴스 수집 " + s.reelsCollectedAccounts() + "명")));
        model.addAttribute("discoveryArchiveCount", s.discoveryArchiveCount());
        // 재방문 주기는 설정값 — 하드코딩 문구 대신 현재 값 표시 (설정 화면에서 무중단 변경)
        model.addAttribute("revisitIntervalDays", settings.revisitIntervalDays());
        return "fragments/status-tiles :: tiles";
    }

    @GetMapping("/ui/fragments/logs")
    public String logsFragment(Model model) {
        model.addAttribute("lines", logBuffer.lines());
        return "fragments/logs :: panel";
    }

    /** 데일리 수집 대시보드 — 하루 단위 스냅샷·릴스 사이클의 오늘 진행. */
    @GetMapping("/ui/daily")
    public String daily(Model model) {
        model.addAttribute("d", statusService.daily());
        model.addAttribute("revisitIntervalDays", settings.revisitIntervalDays());
        return "daily";
    }

    @GetMapping("/ui/jobs")
    public String jobs(Model model) {
        model.addAttribute("costs", jobCostEstimator.estimates());
        return "jobs";
    }

    /** 판정 완료 상태만 — 인플루언서 명단의 범위 불변식. DISCOVERED(판정 전)는 명단 밖. */
    private static final java.util.List<InfluencerStatus> JUDGED_STATUSES =
            java.util.List.of(InfluencerStatus.QUALIFIED, InfluencerStatus.EXCLUDED);

    /** 인플루언서 명단 1행 — 판정 완료된 인플루언서 + 최초 발굴 맥락(키워드·발굴일). */
    public record InfluencerRow(com.celfit.crawler.crawling.domain.Influencer influencer,
                                String discoveredKeyword, java.time.Instant discoveredAt) {}

    @GetMapping("/ui/influencers")
    public String influencers(@RequestParam(required = false) java.util.List<InfluencerStatus> status,
                              @RequestParam(required = false, defaultValue = "false") boolean company,
                              @RequestParam(defaultValue = "0") int page, Model model) {
        var selected = status == null ? java.util.List.<InfluencerStatus>of()
                                      : status.stream().filter(JUDGED_STATUSES::contains).toList();
        var effective = selected.isEmpty() ? JUDGED_STATUSES : selected;
        var pageable = PageRequest.of(Math.max(page, 0), 50, Sort.by(Sort.Direction.DESC, "id"));
        // company=true — 뷰티 회사 리스트업 뷰(수집 제외 계정 확인용)
        var result = company
                ? influencers.findByStatusInAndBeautyTrueAndBeautyCompanyTrue(effective, pageable)
                : influencers.findByStatusIn(effective, pageable);
        model.addAttribute("companyView", company);

        var ids = result.getContent().stream()
                .map(com.celfit.crawler.crawling.domain.Influencer::getId).toList();
        java.util.Map<Long, InfluencerDiscoveryRepository.FirstDiscovery> firstById = new java.util.HashMap<>();
        if (!ids.isEmpty()) {
            for (var d : influencerDiscoveries.findFirstDiscoveries(ids)) {
                firstById.put(d.getInfluencerId(), d);
            }
        }
        model.addAttribute("page", result.map(i -> {
            var first = firstById.get(i.getId());
            return new InfluencerRow(i,
                    first == null ? null : first.getKeyword(),
                    first == null ? null : first.getDiscoveredAt());
        }));
        model.addAttribute("status", selected);
        model.addAttribute("statuses", JUDGED_STATUSES);
        // 수동 오버라이드 버튼용 4분류 목록 — 템플릿 하드코딩 대신 enum 단일 원천
        model.addAttribute("beautyClasses", BeautyClass.values());
        return "influencers";
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

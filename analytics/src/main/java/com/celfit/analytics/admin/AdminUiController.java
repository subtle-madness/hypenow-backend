package com.celfit.analytics.admin;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 어드민 대시보드 — 퍼널·잡 카드·실행 피드·로그 (crawler /ui 계열). cloud one-shot에선 게이트 off. */
@Controller
@ConditionalOnProperty(name = "analytics.admin-enabled", havingValue = "true")
public class AdminUiController {

	private static final Logger log = LoggerFactory.getLogger(AdminUiController.class);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

	/** 대시보드에 노출하는 잡 3종 — CLASSIFY는 휴면(댓글 수집 재개 대기)이라 제외. */
	private static final List<JobName> DASHBOARD_JOBS =
			List.of(JobName.MIRROR, JobName.ANALYZE, JobName.ACCOUNT_ANALYZE);

	/** 잡 카드 뷰모델 — 시각·경과는 컨트롤러에서 KST 포맷해 문자열로 넘긴다(#temporals 미탑재). */
	public record JobCard(JobName job, String label, boolean running,
			int processed, int failed, int total, int percent,
			String elapsedText,   // 실행 중 경과 "Xm", 유휴면 null
			String etaText,       // 실행 중 선형 외삽 KST "HH:mm", 계산 불가면 null
			RunHistory.Run lastRun,   // 이 잡 최근 이력 (없으면 null)
			String lastTimeText,      // lastRun 시각 KST "HH:mm" (없으면 null)
			String nextRunText) {     // 다음 예정 KST "HH:mm" (수동 전용이면 null)
	}

	/** 실행 피드 항목 — 문장·시각을 컨트롤러에서 조립. outcome은 색 점/배지 CSS 클래스용. */
	public record FeedItem(String timeText, String outcome, String sentence) {
	}

	/** 퍼널 카드 뷰모델 — 커버리지·비율·집계 시각을 미리 계산해 템플릿을 단순화. */
	public record FunnelView(long rawContents, long candidates, long timelyExcluded,
			long analyzed, long served,
			long copiedAccounts, long beautyAccounts, boolean candidatesPending,
			String coverageText, int coveragePercent, int todayPlanned, int daysToFull,
			int pinDays, int slackDays, int pinPlusSlackDays,
			int accountPercent, String computedText) {
	}

	private final AnalyticsJobService jobService;
	private final JobProgressRegistry progress;
	private final RunHistory history;
	private final PipelineStatsService stats;
	private final ScheduleInfo scheduleInfo;
	private final LogBuffer logBuffer;

	public AdminUiController(AnalyticsJobService jobService, JobProgressRegistry progress,
			RunHistory history, PipelineStatsService stats, ScheduleInfo scheduleInfo,
			LogBuffer logBuffer) {
		this.jobService = jobService;
		this.progress = progress;
		this.history = history;
		this.stats = stats;
		this.scheduleInfo = scheduleInfo;
		this.logBuffer = logBuffer;
	}

	@GetMapping("/")
	public String root() {
		return "redirect:/ui";
	}

	@GetMapping("/ui")
	public String ui(Model model) {
		// 퍼널 집계 실패에도 페이지는 떠야 한다 — 실패 시 funnel 없이 렌더(템플릿 th:if 가드).
		try {
			model.addAttribute("funnel", funnelView());
		} catch (RuntimeException e) {
			log.warn("퍼널 집계 실패 — 퍼널 카드 없이 렌더", e);
		}
		model.addAttribute("cards", cards());
		model.addAttribute("feed", feed());
		model.addAttribute("scheduleEnabled", scheduleInfo.enabled());
		return "admin";
	}

	/** 5초 폴링 단위 — 카드·피드만 재조립. */
	@GetMapping("/ui/fragments/board")
	public String board(Model model) {
		model.addAttribute("cards", cards());
		model.addAttribute("feed", feed());
		return "fragments/board :: board";
	}

	@PostMapping("/ui/jobs/{slug}")
	public String trigger(@PathVariable String slug, RedirectAttributes ra) {
		JobName job;
		try {
			job = JobName.fromSlug(slug);
		} catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "모르는 잡: " + slug);
		}
		String message = switch (jobService.trigger(job, TriggerType.MANUAL)) {
			case ACCEPTED -> job.label() + " 실행 시작";
			case BUSY -> job.label() + " — 이미 실행 중입니다";
		};
		ra.addFlashAttribute("message", message);
		return "redirect:/ui";
	}

	@GetMapping("/ui/fragments/logs")
	public String logs(Model model) {
		model.addAttribute("lines", logBuffer.lines());
		return "fragments/logs :: panel";
	}

	// ── 조립 ──────────────────────────────────────────────

	private List<JobCard> cards() {
		return DASHBOARD_JOBS.stream().map(this::card).toList();
	}

	private JobCard card(JobName job) {
		JobProgressRegistry.Progress snap = progress.snapshot(job);
		boolean running = jobService.isRunning(job);
		int processed = snap.processed();
		int failed = snap.failed();
		int total = snap.total();
		int percent = total > 0 ? (int) Math.min(100L, Math.round(processed * 100.0 / total)) : 0;
		String elapsedText = running && snap.startedAt() != null ? elapsed(snap.startedAt()) : null;
		String etaText = running ? eta(snap.startedAt(), processed, total) : null;
		RunHistory.Run last = history.recent(50).stream()
				.filter(r -> r.job() == job).findFirst().orElse(null);
		String lastTimeText = last == null ? null : HHMM.format(last.startedAt().atZone(KST));
		// base는 시스템 존 — @Scheduled가 JVM 기본 존(운영 컨테이너=UTC)에서 크론을 해석하므로
		// 실제 발화 시각과 일치시킨다. 표시용 KST 변환은 ScheduleInfo.next()가 담당.
		String nextRunText = scheduleInfo.next(job, ZonedDateTime.now(ZoneId.systemDefault()))
				.map(HHMM::format).orElse(null);
		return new JobCard(job, job.label(), running, processed, failed, total, percent,
				elapsedText, etaText, last, lastTimeText, nextRunText);
	}

	private List<FeedItem> feed() {
		return history.recent(20).stream().map(AdminUiController::feedItem).toList();
	}

	private FunnelView funnelView() {
		PipelineStatsService.Funnel f = stats.funnel();
		boolean pending = f.candidates() < 0;
		int coveragePercent = f.candidates() > 0
				? (int) Math.min(100L, f.analyzed() * 100L / f.candidates()) : 0;
		String coverageText = f.candidates() > 0
				? String.format(Locale.ROOT, "%.1f%%", f.analyzed() * 100.0 / f.candidates()) : "—";
		int accountPercent = f.beautyAccounts() > 0
				? (int) Math.min(100L, f.copiedAccounts() * 100L / f.beautyAccounts()) : 0;
		String computedText = f.heavyComputedAt() == null
				? null : HHMM.format(f.heavyComputedAt().atZone(KST));
		return new FunnelView(f.rawContents(), f.candidates(), f.timelyExcluded(),
				f.analyzed(), f.served(),
				f.copiedAccounts(), f.beautyAccounts(), pending, coverageText, coveragePercent,
				f.todayPlanned(), f.daysToFull(),
				f.pinDays(), f.slackDays(), f.pinDays() + f.slackDays(),
				accountPercent, computedText);
	}

	// ── 포맷 헬퍼 ─────────────────────────────────────────

	/** 진행 중 경과 시간 — 분 단위 "Xm". */
	private static String elapsed(Instant startedAt) {
		return Duration.between(startedAt, Instant.now()).toMinutes() + "m";
	}

	/** 선형 외삽 예상 완료 — startedAt + 경과 * total / processed 를 KST HH:mm. 근거 없으면 null. */
	private static String eta(Instant startedAt, int processed, int total) {
		if (startedAt == null || processed <= 0 || total <= 0) return null;
		long elapsedMs = Duration.between(startedAt, Instant.now()).toMillis();
		long totalMs = elapsedMs * total / processed;
		return HHMM.format(startedAt.plusMillis(totalMs).atZone(KST));
	}

	private static FeedItem feedItem(RunHistory.Run r) {
		String time = HHMM.format(r.startedAt().atZone(KST));
		String trigger = r.trigger() == TriggerType.MANUAL ? "수동" : "자동";
		String verb = switch (r.outcome()) {
			case SUCCESS, FAILED -> "완료";
			case QUOTA_CARRYOVER -> "이월";
			case ERROR -> "오류";
		};
		StringBuilder sb = new StringBuilder(shortLabel(r.job()))
				.append(' ').append(verb).append(" — ").append(r.processed()).append("건 처리");
		if (r.failed() > 0) sb.append(" · 실패 ").append(r.failed());
		sb.append(" (").append(trigger).append(')');
		if (r.outcome() == RunHistory.Outcome.ERROR && r.note() != null) {
			sb.append(" · ").append(r.note());
		}
		return new FeedItem(time, r.outcome().name(), sb.toString());
	}

	/** 라벨에서 부연(" (LLM)"·" — …")을 떼어 짧은 이름만. */
	private static String shortLabel(JobName job) {
		String s = job.label();
		int i = s.indexOf(" (");
		int j = s.indexOf(" — ");
		int cut = i < 0 ? j : (j < 0 ? i : Math.min(i, j));
		return cut < 0 ? s : s.substring(0, cut);
	}
}

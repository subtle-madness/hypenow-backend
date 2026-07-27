package com.celfit.analytics.admin;

import com.celfit.analytics.config.AnalyticsSettings;
import com.celfit.analytics.mirror.MirrorRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

/** 어드민 대시보드 — 건강 스트립·퍼널·잡 카드·실행 피드·로그 (crawler /ui 계열). cloud one-shot에선 게이트 off. */
@Controller
@ConditionalOnProperty(name = "analytics.admin-enabled", havingValue = "true")
public class AdminUiController {

	private static final Logger log = LoggerFactory.getLogger(AdminUiController.class);
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

	/** 대시보드에 활성 잡으로 노출하는 5종. CLASSIFY(댓글 분류)는 휴면 카드로 별도 표시. */
	private static final List<JobName> DASHBOARD_JOBS =
			List.of(JobName.MIRROR, JobName.ANALYZE, JobName.LATE_BACKFILL_ANALYZE,
					JobName.ACCOUNT_ANALYZE, JobName.ARCHIVE);

	/** 잡 카드 뷰모델 — 시각·경과는 컨트롤러에서 KST 포맷해 문자열로 넘긴다(#temporals 미탑재). */
	public record JobCard(JobName job, String label, boolean running,
			int processed, int failed, int total, int percent,
			String elapsedText,   // 실행 중 경과 "Xm", 유휴면 null
			String etaText,       // 실행 중 선형 외삽 KST "HH:mm", 계산 불가면 null
			RunHistory.Run lastRun,   // 이 잡 최근 이력 (없으면 null)
			String lastTimeText,      // lastRun 시각 KST "HH:mm" (없으면 null)
			String nextRunText,       // 다음 예정 KST "HH:mm" (수동 전용이면 null)
			String scopeLine,         // 잡별 대상/완료/잔여 한 줄 (없으면 null)
			String scopeSubLine) {    // 보조 설명 (없으면 null)
	}

	/** 실행 피드 항목 — 문장·시각을 컨트롤러에서 조립. outcome은 색 점/배지 CSS 클래스용. */
	public record FeedItem(String timeText, String outcome, String sentence) {
	}

	/**
	 * 보드 뷰모델 (v3 — 계정 보드·콘텐츠 보드) — 수치는 전부 컨트롤러에서 포맷해 문자열로.
	 * 무거운 집계(크로스 DB 대조) 파생 필드는 집계 전/실패면 null — 템플릿은 3상태(pending/failed/수치)
	 * 가드로 감싼다(v2의 "집계 중" 영구 고정 버그 대응 유지).
	 */
	public record FunnelView(
			// 무거운 집계 3상태 (v2 유지)
			boolean heavyPending, boolean heavyFailed, String candidatesErrorText, String computedText,
			// 계정 보드 — 매 요청 raw 카운트
			String acctTotal, String acctQualified, String acctBeauty, String acctCompany,
			String acctNonBeauty, long judgedToday, String judgedTodayText,
			// 계정 카피 (G1) — 현 모수 기준 + 누적 각주
			String copyBase, String copyHave, int copyPercent, String copyStale, String copyCumulative,
			// 콘텐츠 보드 — 축 수치
			String rawContents, String serving, String candidates,
			String timelyTotal, String timelyDone, String timelyPending,
			String windowTotal, String windowDone, String windowPending,
			String truePending, int todayPlanned, int daysToFull,
			String immature, String lateExcluded,
			// 서빙 커버리지 (G2)
			String servingAnalyzed, String coverageText, int coveragePercent,
			// 누적 각주 (역대 분석 저장 — 모수 리비전 혼재)
			String analyzedTotal, String timelyMarked, String backfillMarked, long otherMarked,
			String otherMarkedText,
			int pinDays, int slackDays, int pinPlusSlackDays) {
	}

	/** 건강 스트립 — 신선도·오늘 처리량·프로바이더·쿼터 이월. 문자열은 전부 컨트롤러에서 조립. */
	public record HealthView(
			String providerLabel, String model, String rpmText,
			long todayAnalyzed, long todayAccountCopied,
			String analysisFreshText, String analysisGrade,
			String mirrorFreshText, String mirrorGrade,
			String accountFreshText, String accountGrade,
			boolean quotaCarryover, String quotaText) {
	}

	private final AnalyticsJobService jobService;
	private final JobProgressRegistry progress;
	private final RunHistory history;
	private final PipelineStatsService stats;
	private final ScheduleInfo scheduleInfo;
	private final LogBuffer logBuffer;
	private final AnalyticsSettings settings;
	private final MirrorRegistry mirrorRegistry;

	public AdminUiController(AnalyticsJobService jobService, JobProgressRegistry progress,
			RunHistory history, PipelineStatsService stats, ScheduleInfo scheduleInfo,
			LogBuffer logBuffer, AnalyticsSettings settings, MirrorRegistry mirrorRegistry) {
		this.jobService = jobService;
		this.progress = progress;
		this.history = history;
		this.stats = stats;
		this.scheduleInfo = scheduleInfo;
		this.logBuffer = logBuffer;
		this.settings = settings;
		this.mirrorRegistry = mirrorRegistry;
	}

	@GetMapping("/")
	public String root() {
		return "redirect:/ui";
	}

	@GetMapping("/ui")
	public String ui(Model model) {
		model.addAttribute("scheduleEnabled", scheduleInfo.enabled());
		return "admin";
	}

	/**
	 * 5초 폴링 단위 — 건강·퍼널·카드·피드를 통째로 재조립한다.
	 * 퍼널을 여기로 옮긴 이유: 후보 수(무거운 캐시)가 집계 완료되면 새로고침 없이 자동 반영되게 하려면
	 * 퍼널이 폴링 대상이어야 한다("집계 중" 영구 고정 버그 해소).
	 */
	@GetMapping("/ui/fragments/board")
	public String board(Model model) {
		PipelineStatsService.Funnel funnel = null;
		try {
			funnel = stats.funnel();
			model.addAttribute("funnel", funnelView(funnel));
			model.addAttribute("health", healthView(stats.health()));
		} catch (RuntimeException e) {
			// 집계 실패에도 잡 카드·피드는 떠야 한다 — 퍼널·건강 없이 렌더(템플릿 th:if 가드).
			log.warn("퍼널·건강 집계 실패 — 카드만 렌더", e);
		}
		model.addAttribute("cards", cards(funnel));
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

	private List<JobCard> cards(PipelineStatsService.Funnel f) {
		return DASHBOARD_JOBS.stream().map(job -> card(job, f)).toList();
	}

	private JobCard card(JobName job, PipelineStatsService.Funnel f) {
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
				elapsedText, etaText, last, lastTimeText, nextRunText,
				scopeLine(job, f), scopeSubLine(job, f));
	}

	/** 잡별 대상/완료/잔여 한 줄 — 집계 수치에서 파생. 집계 전(f==null)이거나 모르는 잡이면 null. */
	private String scopeLine(JobName job, PipelineStatsService.Funnel f) {
		if (f == null) {
			return null;
		}
		PipelineStatsService.Heavy h = f.heavy();
		return switch (job) {
			case ANALYZE -> {
				if (h == null) {
					yield f.candidatesError() != null ? "대상 집계 실패 — 분석 뷰 확인 필요" : "대상 집계 중…";
				}
				// timely 트랙만 표시(2026-07-23 분리) — late_backfill 트랙은 LATE_BACKFILL_ANALYZE
				// 카드가 별도 표시. 예전엔 한 잡이 둘 다 처리해 합산이 맞았지만, 분리 후 합산을 그대로
				// 이 카드에 두면 "콘텐츠 분석이 N건 밀렸다"로 오독된다(실제론 백필 잡 미가동 때문).
				yield "후보 %s · 기분석 %s · 미분석 %s".formatted(
						comma(h.timelyTotal()), comma(h.timelyDone()), comma(h.timelyPending()));
			}
			case LATE_BACKFILL_ANALYZE -> {
				if (h == null) {
					yield f.candidatesError() != null ? "대상 집계 실패 — 분석 뷰 확인 필요" : "대상 집계 중…";
				}
				yield "후보 %s · 기분석 %s · 미분석 %s".formatted(
						comma(h.windowTotal()), comma(h.windowDone()), comma(h.windowPending()));
			}
			// 계정 카피 완료율도 현 모수 기준(G1) — 누적 핸들(탈락 계정 포함)로 세면 >100%가 나온다.
			case ACCOUNT_ANALYZE -> h == null
					? "카피 누적 %s · 대상 %s".formatted(comma(f.copiedAccounts()), comma(f.accountTarget()))
					: "현 모수 %s 중 카피 %s · 대상 %s".formatted(
							comma(h.beautyHandles()), comma(h.beautyCopied()), comma(f.accountTarget()));
			// 미러도 "몇 개를 옮겼는지"가 보여야 한다 — 대상 뷰 수와 적재 결과(미러 세계의 게시물·계정).
			case MIRROR -> "대상 %d개 뷰 · 게시물 %s · 계정 %s".formatted(
					mirrorRegistry.specs().size(), comma(f.served()), comma(f.mirrorAccounts()));
			// 아카이브 — 대상(썸네일+프로필) 대비 얼마나 옮겨졌는지. 잔여 분해는 서브라인.
			case ARCHIVE -> {
				if (h == null) {
					yield f.candidatesError() != null ? "대상 집계 실패 — 분석 뷰 확인 필요" : "대상 집계 중…";
				}
				PipelineStatsService.ArchiveCoverage c = h.archive();
				int percent = c.targets() > 0 ? (int) (c.archived() * 100 / c.targets()) : 0;
				yield "대상 %s · 아카이브 %s (%d%%) · 미아카이브 %s".formatted(
						comma(c.targets()), comma(c.archived()), percent, comma(c.pending()));
			}
			default -> null;
		};
	}

	private String scopeSubLine(JobName job, PipelineStatsService.Funnel f) {
		if (f == null) {
			return null;
		}
		return switch (job) {
			// 잔여 미상(집계 중·실패)이면 todayPlanned은 0이 아니라 "모름" — "+0 예정"은 오독을 부른다.
			// LIMIT 폐지(2026-07-23) 이후 잡은 자기 트랙의 잔여 전량을 오늘 시도 — 트랙별 잔여를 그대로 쓴다.
			case ANALYZE -> f.heavy() == null ? "오늘 예정량 미상"
					: "오늘 +%s 예정 · 랭킹 노출은 제때(timely) 분석분만".formatted(comma(f.heavy().timelyPending()));
			case LATE_BACKFILL_ANALYZE -> f.heavy() == null ? "오늘 예정량 미상"
					: "오늘 +%s 예정 · 인플루언서 상세에만 노출".formatted(comma(f.heavy().windowPending()));
			case ACCOUNT_ANALYZE -> "stale + 쿨다운 경과분 재분석";
			case MIRROR -> "분석 무관 — 서빙 뷰 전체 재적재";
			// 미아카이브 중 CDN 만료(~4일)분은 재크롤로 URL이 갱신돼야 구제 — 수치는 30분 캐시.
			case ARCHIVE -> f.heavy() == null ? null
					: "썸네일 잔여 %s · 프로필 갱신 대기 %s".formatted(
							comma(f.heavy().archive().thumbPending()),
							comma(f.heavy().archive().profilePending()));
			default -> null;
		};
	}

	private List<FeedItem> feed() {
		return history.recent(20).stream().map(AdminUiController::feedItem).toList();
	}

	private FunnelView funnelView(PipelineStatsService.Funnel f) {
		PipelineStatsService.Heavy h = f.heavy();
		// 캐시 없음 + 마지막 시도 실패 = "집계 실패"(뷰 미적용·드리프트 등). 실패 사유가 없으면 순수 "집계 중".
		boolean failed = h == null && f.candidatesError() != null;
		boolean pending = h == null && !failed;
		PipelineStatsService.Accounts a = f.accounts();
		// 커버리지(G2): 분모·분자 모두 "현재 서빙 모수" — 누적 분석 수는 모수 리비전이 섞여 각주로 강등.
		int coveragePercent = h != null && h.servingContents() > 0
				? (int) Math.min(100L, h.servingAnalyzed() * 100L / h.servingContents()) : 0;
		String coverageText = h != null && h.servingContents() > 0
				? String.format(Locale.ROOT, "%.1f%%", h.servingAnalyzed() * 100.0 / h.servingContents())
				: "—";
		// 계정 카피율(G1)도 현 모수 교집합 기준 — 누적 핸들/현 모수로 세면 >100%가 나온다.
		int copyPercent = h != null && h.beautyHandles() > 0
				? (int) Math.min(100L, h.beautyCopied() * 100L / h.beautyHandles()) : 0;
		String computedText = h == null ? null : HHMM.format(h.computedAt().atZone(KST));
		// immature·마킹 전(NULL) 레거시 — timely/backfill 어느 쪽도 아닌 기분석분.
		long other = Math.max(0, f.analyzed() - f.timelyMarked() - f.backfillMarked());
		return new FunnelView(pending, failed, f.candidatesError(), computedText,
				comma(a.total()), comma(a.qualified()), comma(a.beautyIndividual()),
				comma(a.beautyCompany()), comma(a.nonBeauty()),
				a.judgedToday(), comma(a.judgedToday()),
				h == null ? null : comma(h.beautyHandles()),
				h == null ? null : comma(h.beautyCopied()),
				copyPercent, comma(f.accountTarget()), comma(f.copiedAccounts()),
				comma(f.rawContents()),
				h == null ? null : comma(h.servingContents()),
				h == null ? null : comma(h.candidates()),
				h == null ? null : comma(h.timelyTotal()),
				h == null ? null : comma(h.timelyDone()),
				h == null ? null : comma(h.timelyPending()),
				h == null ? null : comma(h.windowTotal()),
				h == null ? null : comma(h.windowDone()),
				h == null ? null : comma(h.windowPending()),
				h == null ? null : comma(h.truePending()),
				f.todayPlanned(), f.daysToFull(),
				h == null ? null : comma(h.immaturePool()),
				h == null ? null : comma(h.lateExcluded()),
				h == null ? null : comma(h.servingAnalyzed()),
				coverageText, coveragePercent,
				comma(f.analyzed()), comma(f.timelyMarked()), comma(f.backfillMarked()),
				other, comma(other),
				f.pinDays(), f.slackDays(), f.pinDays() + f.slackDays());
	}

	private HealthView healthView(PipelineStatsService.Health h) {
		// 프로바이더 — vertex 전환·폴백 상황에서 "무엇으로 도는가"를 한눈에.
		String provider = settings.llmProvider();
		String providerLabel = switch (provider) {
			case "vertex" -> "Vertex AI";
			case "gemini" -> "Gemini (무료 티어)";
			case "anthropic" -> "Anthropic";
			default -> provider;
		};
		String rpmText = switch (provider) {
			case "gemini" -> settings.geminiRpm() + " RPM";
			case "vertex" -> "페이싱 없음 (DSQ)";
			default -> null;
		};
		QuotaState quota = quotaState();
		return new HealthView(providerLabel, settings.activeLlmModel(), rpmText,
				h.todayAnalyzed(), h.todayAccountCopied(),
				freshText(h.lastAnalysisAt()), freshGrade(h.lastAnalysisAt()),
				freshText(h.lastMirrorAt()), freshGrade(h.lastMirrorAt()),
				freshText(h.lastAccountCopyAt()), freshGrade(h.lastAccountCopyAt()),
				quota.carryover(), quota.text());
	}

	private record QuotaState(boolean carryover, String text) {
	}

	/** 각 대시보드 잡의 최근 실행이 QUOTA_CARRYOVER면 이월 중 — LLM 일 한도 소진 신호를 상단에 노출. */
	private QuotaState quotaState() {
		List<String> jobs = new ArrayList<>();
		for (JobName job : DASHBOARD_JOBS) {
			RunHistory.Run last = history.recent(50).stream()
					.filter(r -> r.job() == job).findFirst().orElse(null);
			if (last != null && last.outcome() == RunHistory.Outcome.QUOTA_CARRYOVER) {
				jobs.add(shortLabel(job));
			}
		}
		return jobs.isEmpty()
				? new QuotaState(false, null)
				: new QuotaState(true, String.join(", ", jobs) + " — LLM 일 한도 소진, 잔여 이월");
	}

	// ── 포맷 헬퍼 ─────────────────────────────────────────

	private static String comma(long v) {
		return String.format(Locale.ROOT, "%,d", v);
	}

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

	/** 신선도 문구 — "방금"·"N분 전"·"N시간 전 (HH:mm)"·"어제 HH:mm"·"N일 전 HH:mm". 없으면 "—". */
	static String freshText(Instant at) {
		if (at == null) {
			return "—";
		}
		Duration d = Duration.between(at, Instant.now());
		long mins = d.toMinutes();
		if (mins < 1) return "방금";
		if (mins < 60) return mins + "분 전";
		long hours = d.toHours();
		String hhmm = HHMM.format(at.atZone(KST));
		if (hours < 24) return hours + "시간 전 (" + hhmm + ")";
		long days = d.toDays();
		return days == 1 ? "어제 " + hhmm : days + "일 전 " + hhmm;
	}

	/** 신선도 등급 — 야간 잡 1일 주기 기준: 26h 이내 정상, 50h 이내 주의, 초과 경고. 없으면 주의. */
	static String freshGrade(Instant at) {
		if (at == null) {
			return "warn";
		}
		long hours = Duration.between(at, Instant.now()).toHours();
		if (hours <= 26) return "ok";
		if (hours <= 50) return "warn";
		return "bad";
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

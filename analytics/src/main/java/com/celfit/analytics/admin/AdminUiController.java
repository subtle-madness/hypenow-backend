package com.celfit.analytics.admin;

import java.util.Arrays;
import java.util.List;
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

/** 어드민 화면 — 잡 트리거·비용 카드·상태·로그 (crawler /ui 패턴). cloud one-shot에선 게이트 off. */
@Controller
@ConditionalOnProperty(name = "analytics.admin-enabled", havingValue = "true")
public class AdminUiController {

	private static final Logger log = LoggerFactory.getLogger(AdminUiController.class);

	public record JobStatus(String label, boolean running) {}

	private final AnalyticsJobService jobService;
	private final JobCostEstimator costEstimator;
	private final LogBuffer logBuffer;

	public AdminUiController(AnalyticsJobService jobService, JobCostEstimator costEstimator,
			LogBuffer logBuffer) {
		this.jobService = jobService;
		this.costEstimator = costEstimator;
		this.logBuffer = logBuffer;
	}

	@GetMapping("/")
	public String root() {
		return "redirect:/ui";
	}

	@GetMapping("/ui")
	public String ui(Model model) {
		model.addAttribute("jobs", JobName.values());
		// 분석 뷰 미적용 등으로 대상 카운트가 실패해도 트리거·로그 패널은 쓸 수 있어야 한다
		try {
			model.addAttribute("costs", costEstimator.costCards());
		} catch (RuntimeException e) {
			log.warn("비용 추정 실패 — 카드 없이 렌더", e);
			model.addAttribute("costs", List.of());
			model.addAttribute("costError", "비용 추정 실패: " + e.getMessage());
		}
		return "admin";
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

	@GetMapping("/ui/fragments/status")
	public String status(Model model) {
		List<JobStatus> statuses = Arrays.stream(JobName.values())
				.map(j -> new JobStatus(j.label(), jobService.isRunning(j)))
				.toList();
		model.addAttribute("statuses", statuses);
		return "fragments/status :: badges";
	}
}

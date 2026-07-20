package com.celfit.analytics.coverage;

import java.time.LocalDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** 커버리지 화면 — 수집(raw)→미러·분석(analysis) 채움 상태. 어드민 전용(내부 화면 — was 고객 표면에서 이전). */
@Controller
@ConditionalOnProperty(name = "analytics.admin-enabled", havingValue = "true")
public class CoverageController {

	private final CoverageRepository coverageRepository;

	public CoverageController(CoverageRepository coverageRepository) {
		this.coverageRepository = coverageRepository;
	}

	@GetMapping("/ui/coverage")
	public String coverage(Model model) {
		model.addAttribute("source", coverageRepository.source());
		model.addAttribute("tiles", coverageRepository.tiles());
		model.addAttribute("matrix", coverageRepository.matrix());
		model.addAttribute("queriedAt", LocalDateTime.now());
		return "coverage";
	}
}

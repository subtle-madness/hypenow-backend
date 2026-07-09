package com.celfit.was.dashboard;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

	private final AnalysisRepository analysisRepository;

	public DashboardController(AnalysisRepository analysisRepository) {
		this.analysisRepository = analysisRepository;
	}

	@GetMapping("/")
	public String dashboard(Model model) {
		model.addAttribute("ranking", analysisRepository.contentRanking());
		model.addAttribute("categories", analysisRepository.categoryPerformance());
		model.addAttribute("creators", analysisRepository.creatorPerformance());
		model.addAttribute("overperformers", analysisRepository.overperformers());
		model.addAttribute("tiers", analysisRepository.tierDistribution());
		model.addAttribute("contentTypes", analysisRepository.contentType());
		model.addAttribute("hashtags", analysisRepository.hashtags());
		model.addAttribute("freshness", analysisRepository.freshness());
		return "dashboard";
	}
}

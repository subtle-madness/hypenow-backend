package com.celfit.was.coverage;

import java.time.LocalDateTime;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class CoverageController {

	private final CoverageRepository coverageRepository;

	public CoverageController(CoverageRepository coverageRepository) {
		this.coverageRepository = coverageRepository;
	}

	@GetMapping("/coverage")
	public String coverage(Model model) {
		model.addAttribute("tiles", coverageRepository.tiles());
		model.addAttribute("matrix", coverageRepository.matrix());
		model.addAttribute("queriedAt", LocalDateTime.now());
		return "coverage";
	}
}

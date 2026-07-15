package com.celfit.was.coverage;

import com.celfit.was.postdemo.PostDemoRepository;
import java.time.LocalDateTime;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class CoverageController {

	private final CoverageRepository coverageRepository;
	private final PostDemoRepository postDetailRepository;

	public CoverageController(CoverageRepository coverageRepository, PostDemoRepository postDetailRepository) {
		this.coverageRepository = coverageRepository;
		this.postDetailRepository = postDetailRepository;
	}

	@GetMapping("/coverage")
	public String coverage(Model model) {
		model.addAttribute("tiles", coverageRepository.tiles());
		model.addAttribute("matrix", coverageRepository.matrix());
		model.addAttribute("analyzedPosts", postDetailRepository.analyzedPosts());
		model.addAttribute("queriedAt", LocalDateTime.now());
		return "coverage";
	}
}

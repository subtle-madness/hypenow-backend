package com.celfit.was.postdemo;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Controller
public class PostDemoController {

	private final PostDemoRepository postDetailRepository;
	private final ObjectMapper json = new ObjectMapper();

	public PostDemoController(PostDemoRepository postDetailRepository) {
		this.postDetailRepository = postDetailRepository;
	}

	private static final Map<String, String> CATEGORY_LABELS = Map.of(
			"purchase", "구매 의향", "question", "질문", "positive", "긍정 반응",
			"adAware", "광고 인지", "friendTag", "친구 태그", "etc", "기타");

	@GetMapping("/posts/{shortCode}")
	public String post(@PathVariable String shortCode, Model model) {
		Map<String, Object> post = postDetailRepository.find(shortCode)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "게시물 없음: " + shortCode));
		model.addAttribute("post", post);
		model.addAttribute("brands", parseList(post.get("detected_brands")));
		model.addAttribute("signalReasons", parseList(post.get("sponsored_signal_reasons")));
		model.addAttribute("productCategories", parseList(post.get("detected_product_categories")));
		model.addAttribute("vlmAttributes", parseList(post.get("vlm_attributes")));
		model.addAttribute("subCategories", parseList(post.get("sub_categories")));
		model.addAttribute("breakdown", postDetailRepository.commentBreakdown(shortCode));
		model.addAttribute("topComments", postDetailRepository.topComments(shortCode, 5));
		model.addAttribute("categoryLabels", CATEGORY_LABELS);
		return "post";
	}

	private List<?> parseList(Object jsonb) {
		if (jsonb == null) {
			return List.of();
		}
		try {
			return json.readValue((String) jsonb, List.class);
		} catch (RuntimeException e) {
			return List.of();
		}
	}
}

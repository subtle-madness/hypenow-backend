package com.celfit.crawler.crawling.adapter.in.web;

import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.Influencer;
import com.celfit.crawler.crawling.domain.InfluencerStatus;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 명단 페이지 뷰티 수동 오버라이드(3분류: 비뷰티/뷰티 인플루언서/뷰티 회사) —
 * MANUAL 출처는 BEAUTY 잡 재판정에서도 보존된다.
 */
@Controller
public class InfluencerBeautyController {

    private final InfluencerRepository influencers;

    public InfluencerBeautyController(InfluencerRepository influencers) {
        this.influencers = influencers;
    }

    @PostMapping("/ui/influencers/{id}/beauty")
    public String override(@PathVariable Long id, @RequestParam boolean beauty,
                           @RequestParam(defaultValue = "false") boolean company,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(required = false) List<InfluencerStatus> status,
                           RedirectAttributes ra) {
        Influencer inf = influencers.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "인플루언서 없음"));
        inf.setBeauty(beauty);
        inf.setBeautyCompany(beauty && company);
        inf.setBeautySource(Influencer.BEAUTY_SOURCE_MANUAL);
        inf.setBeautyReason("수동 판정");
        influencers.save(inf);
        ra.addAttribute("page", page);
        if (status != null && !status.isEmpty()) ra.addAttribute("status", status);
        return "redirect:/ui/influencers";
    }
}

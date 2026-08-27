package com.celfit.crawler.crawling.adapter.in.web;

import com.celfit.crawler.crawling.application.port.out.InfluencerRepository;
import com.celfit.crawler.crawling.domain.BeautyClass;
import com.celfit.crawler.crawling.domain.CategoryClass;
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
 * 명단 페이지 수동 오버라이드 — 뷰티 축(5분류: 뷰티 인플루언서/뷰티 회사/시술·서비스/외국인/뷰티 아님)과
 * F&amp;B·홈/리빙 축(각 CategoryClass 5분류)을 각각 독립으로 덮어쓴다.
 * MANUAL 출처는 BEAUTY 잡이 덮지 않는다 — 축별로 보호 지점이 다르다.
 * 뷰티 MANUAL은 rejudge 선정 쿼리(beauty_source='CLAUDE')에서 제외돼 보존되고,
 * F&amp;B·홈/리빙 MANUAL은 어느 경로로 선정되든 적용 시점 가드(source='MANUAL'이면 해당 축 미적용)로
 * 보존된다.
 */
@Controller
public class InfluencerBeautyController {

    private final InfluencerRepository influencers;

    public InfluencerBeautyController(InfluencerRepository influencers) {
        this.influencers = influencers;
    }

    @PostMapping("/ui/influencers/{id}/beauty")
    public String override(@PathVariable Long id, @RequestParam BeautyClass beautyClass,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(required = false) List<InfluencerStatus> status,
                           @RequestParam(required = false) List<String> beauty,
                           @RequestParam(required = false) List<String> fnb,
                           @RequestParam(required = false) List<String> homeLiving,
                           RedirectAttributes ra) {
        Influencer inf = influencers.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "인플루언서 없음"));
        inf.classify(beautyClass, Influencer.BEAUTY_SOURCE_MANUAL, "수동 판정", null);
        influencers.save(inf);
        keepFilters(ra, page, status, beauty, fnb, homeLiving);
        return "redirect:/ui/influencers";
    }

    /**
     * F&B 축 수동 오버라이드 — MANUAL 출처는 백필 선정(fnb IS NULL)에서 자연 제외되고,
     * 뷰티 rejudge·신규 판정 경로로 같은 계정이 다시 잡혀도 BeautyJob의 적용 시점 가드
     * (fnb_source='MANUAL')가 F&B 축을 덮지 않는다.
     */
    @PostMapping("/ui/influencers/{id}/fnb")
    public String overrideFnb(@PathVariable Long id, @RequestParam CategoryClass fnbClass,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(required = false) List<InfluencerStatus> status,
                              @RequestParam(required = false) List<String> beauty,
                              @RequestParam(required = false) List<String> fnb,
                              @RequestParam(required = false) List<String> homeLiving,
                              RedirectAttributes ra) {
        Influencer inf = influencers.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "인플루언서 없음"));
        inf.classifyFnb(fnbClass, Influencer.BEAUTY_SOURCE_MANUAL, "수동 판정", null);
        influencers.save(inf);
        keepFilters(ra, page, status, beauty, fnb, homeLiving);
        return "redirect:/ui/influencers";
    }

    /** 홈/리빙 축 수동 오버라이드 — 적용 시점 가드(home_living_source='MANUAL')가 자동 판정을 막는다. */
    @PostMapping("/ui/influencers/{id}/homeLiving")
    public String overrideHomeLiving(@PathVariable Long id, @RequestParam CategoryClass homeLivingClass,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(required = false) List<InfluencerStatus> status,
                                     @RequestParam(required = false) List<String> beauty,
                                     @RequestParam(required = false) List<String> fnb,
                                     @RequestParam(required = false) List<String> homeLiving,
                                     RedirectAttributes ra) {
        Influencer inf = influencers.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "인플루언서 없음"));
        inf.classifyHomeLiving(homeLivingClass, Influencer.BEAUTY_SOURCE_MANUAL, "수동 판정", null);
        influencers.save(inf);
        keepFilters(ra, page, status, beauty, fnb, homeLiving);
        return "redirect:/ui/influencers";
    }

    /** 오버라이드 후 리다이렉트에 명단 필터 상태를 그대로 실어 사용자의 필터 컨텍스트를 보존한다. */
    private static void keepFilters(RedirectAttributes ra, int page, List<InfluencerStatus> status,
                                    List<String> beauty, List<String> fnb, List<String> homeLiving) {
        ra.addAttribute("page", page);
        if (status != null && !status.isEmpty()) ra.addAttribute("status", status);
        if (beauty != null && !beauty.isEmpty()) ra.addAttribute("beauty", beauty);
        if (fnb != null && !fnb.isEmpty()) ra.addAttribute("fnb", fnb);
        if (homeLiving != null && !homeLiving.isEmpty()) ra.addAttribute("homeLiving", homeLiving);
    }
}

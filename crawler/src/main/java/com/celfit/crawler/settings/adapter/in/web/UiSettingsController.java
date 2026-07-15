package com.celfit.crawler.settings.adapter.in.web;

import com.celfit.crawler.crawling.adapter.out.instagram.ProxyProperties;
import com.celfit.crawler.settings.application.service.CommentSourceSetting;
import com.celfit.crawler.settings.application.service.DiscoverSourceSetting;
import com.celfit.crawler.settings.application.service.ProfileSourceSetting;
import com.celfit.crawler.settings.application.service.ProfileSupplementSetting;
import com.celfit.crawler.settings.application.service.ProxySourceSetting;
import com.celfit.crawler.settings.application.service.SettingsService;
import com.celfit.crawler.settings.domain.ProxySource;
import com.celfit.crawler.settings.application.service.SettingsService.SettingView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/ui/settings")
public class UiSettingsController {

    private final SettingsService service;
    private final CommentSourceSetting commentSourceSetting;
    private final DiscoverSourceSetting discoverSourceSetting;
    private final ProfileSourceSetting profileSourceSetting;
    private final ProfileSupplementSetting profileSupplementSetting;
    private final ProxySourceSetting proxySourceSetting;
    private final ProxyProperties proxyProperties;

    public UiSettingsController(SettingsService service, CommentSourceSetting commentSourceSetting,
                                 DiscoverSourceSetting discoverSourceSetting,
                                 ProfileSourceSetting profileSourceSetting,
                                 ProfileSupplementSetting profileSupplementSetting,
                                 ProxySourceSetting proxySourceSetting,
                                 ProxyProperties proxyProperties) {
        this.service = service;
        this.commentSourceSetting = commentSourceSetting;
        this.discoverSourceSetting = discoverSourceSetting;
        this.profileSourceSetting = profileSourceSetting;
        this.profileSupplementSetting = profileSupplementSetting;
        this.proxySourceSetting = proxySourceSetting;
        this.proxyProperties = proxyProperties;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("settings", service.list());
        model.addAttribute("commentSource", commentSourceSetting.current().name());
        model.addAttribute("discoverSource", discoverSourceSetting.current().name());
        model.addAttribute("profileSource", profileSourceSetting.current().name());
        model.addAttribute("profileRelated", profileSupplementSetting.relatedEnabled());
        model.addAttribute("proxySource", proxySourceSetting.current().name());
        // 각 프록시 소스에 URL이 실제로 설정돼 있는지 — UI에서 "미설정→직접 폴백"을 표시하기 위함.
        Map<String, Boolean> proxyConfigured = new java.util.LinkedHashMap<>();
        for (ProxySource s : ProxySource.values()) {
            proxyConfigured.put(s.name(), s == ProxySource.DIRECT || proxyProperties.urlFor(s) != null);
        }
        model.addAttribute("proxyConfigured", proxyConfigured);
        return "settings";
    }

    /** 한 폼에서 6개 필드를 한꺼번에 저장. 빈칸은 리셋(기본값 복귀). */
    @PostMapping
    public String save(@RequestParam Map<String, String> form, RedirectAttributes ra) {
        List<String> errors = new ArrayList<>();
        for (SettingView v : service.list()) {
            String raw = form.get(v.key());
            try {
                Integer value = (raw == null || raw.isBlank()) ? null : Integer.valueOf(raw.trim());
                service.update(v.key(), value);
            } catch (NumberFormatException e) {
                errors.add(v.key() + ": 숫자만 입력 가능");
            } catch (ResponseStatusException e) {
                errors.add(e.getReason());
            }
        }
        ra.addFlashAttribute("message", errors.isEmpty() ? "설정이 저장되었습니다" : String.join(", ", errors));
        return "redirect:/ui/settings";
    }
}

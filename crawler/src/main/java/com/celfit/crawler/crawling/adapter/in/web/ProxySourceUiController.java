package com.celfit.crawler.crawling.adapter.in.web;

import com.celfit.crawler.settings.application.service.ProxySourceSetting;
import com.celfit.crawler.settings.domain.ProxySource;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ProxySourceUiController {

    private final ProxySourceSetting setting;

    public ProxySourceUiController(ProxySourceSetting setting) {
        this.setting = setting;
    }

    @PostMapping("/ui/proxy-source")
    public String update(@RequestParam String source) {
        setting.update(ProxySource.valueOf(source.toUpperCase(Locale.ROOT)));
        return "redirect:/ui/settings";
    }
}

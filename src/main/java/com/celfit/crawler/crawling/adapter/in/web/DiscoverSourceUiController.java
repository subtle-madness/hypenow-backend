package com.celfit.crawler.crawling.adapter.in.web;

import com.celfit.crawler.settings.application.service.DiscoverSourceSetting;
import com.celfit.crawler.settings.domain.DiscoverSource;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class DiscoverSourceUiController {

    private final DiscoverSourceSetting setting;

    public DiscoverSourceUiController(DiscoverSourceSetting setting) {
        this.setting = setting;
    }

    @PostMapping("/ui/discover-source")
    public String update(@RequestParam String source) {
        setting.update(DiscoverSource.valueOf(source.toUpperCase(Locale.ROOT)));
        return "redirect:/ui/settings";
    }
}

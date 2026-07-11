package com.celfit.crawler.crawling.adapter.in.web;

import com.celfit.crawler.settings.application.service.DetailSourceSetting;
import com.celfit.crawler.settings.domain.DetailSource;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class DetailSourceUiController {

    private final DetailSourceSetting setting;

    public DetailSourceUiController(DetailSourceSetting setting) {
        this.setting = setting;
    }

    @PostMapping("/ui/detail-source")
    public String update(@RequestParam String reels, @RequestParam String feed) {
        setting.update(DetailSource.valueOf(reels.toUpperCase(Locale.ROOT)),
                DetailSource.valueOf(feed.toUpperCase(Locale.ROOT)));
        return "redirect:/ui/settings";
    }
}

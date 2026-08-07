package com.celfit.crawler.crawling.adapter.in.web;

import com.celfit.crawler.settings.application.service.ReelsSourceSetting;
import com.celfit.crawler.settings.domain.ReelsSource;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ReelsSourceUiController {

    private final ReelsSourceSetting sourceSetting;

    public ReelsSourceUiController(ReelsSourceSetting sourceSetting) {
        this.sourceSetting = sourceSetting;
    }

    @PostMapping("/ui/reels-source")
    public String update(@RequestParam String source) {
        sourceSetting.update(ReelsSource.valueOf(source.toUpperCase(Locale.ROOT)));
        return "redirect:/ui/settings";
    }
}

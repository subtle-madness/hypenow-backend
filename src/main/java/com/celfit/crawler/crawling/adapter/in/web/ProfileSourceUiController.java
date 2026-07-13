package com.celfit.crawler.crawling.adapter.in.web;

import com.celfit.crawler.settings.application.service.ProfileSourceSetting;
import com.celfit.crawler.settings.application.service.ProfileSupplementSetting;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ProfileSourceUiController {

    private final ProfileSourceSetting sourceSetting;
    private final ProfileSupplementSetting supplementSetting;

    public ProfileSourceUiController(ProfileSourceSetting sourceSetting,
                                      ProfileSupplementSetting supplementSetting) {
        this.sourceSetting = sourceSetting;
        this.supplementSetting = supplementSetting;
    }

    @PostMapping("/ui/profile-source")
    public String update(@RequestParam String source,
                          @RequestParam(defaultValue = "false") boolean related) {
        sourceSetting.update(ProfileSource.valueOf(source.toUpperCase(Locale.ROOT)));
        supplementSetting.update(related);
        return "redirect:/ui/settings";
    }
}

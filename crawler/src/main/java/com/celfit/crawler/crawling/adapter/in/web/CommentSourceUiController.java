package com.celfit.crawler.crawling.adapter.in.web;

import com.celfit.crawler.settings.application.service.CommentSourceSetting;
import com.celfit.crawler.settings.domain.CommentSource;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class CommentSourceUiController {

    private final CommentSourceSetting setting;

    public CommentSourceUiController(CommentSourceSetting setting) {
        this.setting = setting;
    }

    @PostMapping("/ui/comment-source")
    public String update(@RequestParam String source) {
        setting.update(CommentSource.valueOf(source.toUpperCase(Locale.ROOT)));
        return "redirect:/ui/settings";
    }
}

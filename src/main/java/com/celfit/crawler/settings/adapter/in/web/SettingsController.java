package com.celfit.crawler.settings.adapter.in.web;

import com.celfit.crawler.settings.application.service.SettingsService;

import com.celfit.crawler.settings.application.service.SettingsService.SettingView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/settings")
public class SettingsController {

    public record UpdateReq(Integer value) {}

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping
    public List<SettingView> list() {
        return service.list();
    }

    /** value:null은 오버라이드 삭제(기본값 복귀). */
    @PutMapping("/{key}")
    public SettingView update(@PathVariable String key, @RequestBody UpdateReq req) {
        return service.update(key, req.value());
    }
}

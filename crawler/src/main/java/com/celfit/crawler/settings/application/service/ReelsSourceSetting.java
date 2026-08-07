package com.celfit.crawler.settings.application.service;

import com.celfit.crawler.settings.application.port.out.AppSettingRepository;
import com.celfit.crawler.settings.domain.AppSetting;
import com.celfit.crawler.settings.domain.ReelsSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 릴스 수집 소스 토글. app_setting 키 reels.source, 없거나 이상하면 HIKER. */
@Service
public class ReelsSourceSetting {

    static final String KEY = "reels.source";

    private final AppSettingRepository settings;

    public ReelsSourceSetting(AppSettingRepository settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public ReelsSource current() {
        return settings.findById(KEY).map(AppSetting::getValue).map(this::parse).orElse(ReelsSource.HIKER);
    }

    @Transactional
    public void update(ReelsSource source) {
        settings.save(new AppSetting(KEY, source.name()));
    }

    @Transactional
    public void updateRaw(String value) {
        settings.save(new AppSetting(KEY, value));
    }

    private ReelsSource parse(String value) {
        try {
            return ReelsSource.valueOf(value);
        } catch (IllegalArgumentException e) {
            return ReelsSource.HIKER;
        }
    }
}

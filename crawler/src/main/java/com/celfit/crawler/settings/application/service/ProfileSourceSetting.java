package com.celfit.crawler.settings.application.service;

import com.celfit.crawler.settings.application.port.out.AppSettingRepository;
import com.celfit.crawler.settings.domain.AppSetting;
import com.celfit.crawler.settings.domain.ProfileSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 프로필 수집 소스 토글. app_setting 키 profile.source, 없거나 이상하면 SELF. */
@Service
public class ProfileSourceSetting {

    static final String KEY = "profile.source";

    private final AppSettingRepository settings;

    public ProfileSourceSetting(AppSettingRepository settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public ProfileSource current() {
        return settings.findById(KEY).map(AppSetting::getValue).map(this::parse).orElse(ProfileSource.SELF);
    }

    @Transactional
    public void update(ProfileSource source) {
        settings.save(new AppSetting(KEY, source.name()));
    }

    @Transactional
    public void updateRaw(String value) {
        settings.save(new AppSetting(KEY, value));
    }

    private ProfileSource parse(String value) {
        try {
            return ProfileSource.valueOf(value);
        } catch (IllegalArgumentException e) {
            return ProfileSource.SELF;
        }
    }
}

package com.celfit.crawler.settings.application.service;

import com.celfit.crawler.settings.application.port.out.AppSettingRepository;
import com.celfit.crawler.settings.domain.AppSetting;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 프로필 보충(HikerAPI 추가 호출) on/off. 키 profile.supplement.posts / .related. */
@Service
public class ProfileSupplementSetting {

    static final String POSTS = "profile.supplement.posts";
    static final String RELATED = "profile.supplement.related";

    private final AppSettingRepository settings;

    public ProfileSupplementSetting(AppSettingRepository settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public boolean postsEnabled() { return read(POSTS); }

    @Transactional(readOnly = true)
    public boolean relatedEnabled() { return read(RELATED); }

    @Transactional
    public void update(boolean posts, boolean related) {
        settings.save(new AppSetting(POSTS, Boolean.toString(posts)));
        settings.save(new AppSetting(RELATED, Boolean.toString(related)));
    }

    private boolean read(String key) {
        return settings.findById(key).map(AppSetting::getValue).map(Boolean::parseBoolean).orElse(false);
    }
}

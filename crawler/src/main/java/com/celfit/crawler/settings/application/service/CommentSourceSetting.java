package com.celfit.crawler.settings.application.service;

import com.celfit.crawler.settings.application.port.out.AppSettingRepository;
import com.celfit.crawler.settings.domain.AppSetting;
import com.celfit.crawler.settings.domain.CommentSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 댓글 수집 방식 토글. app_setting 키 comment.source, 값이 없거나 이상하면 ACTOR. */
@Service
public class CommentSourceSetting {

    static final String KEY = "comment.source";

    private final AppSettingRepository settings;

    public CommentSourceSetting(AppSettingRepository settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public CommentSource current() {
        return settings.findById(KEY)
                .map(AppSetting::getValue)
                .map(this::parse)
                .orElse(CommentSource.ACTOR);
    }

    @Transactional
    public void update(CommentSource source) {
        settings.save(new AppSetting(KEY, source.name()));
    }

    /** 테스트/방어용 — 임의 문자열 저장. */
    @Transactional
    public void updateRaw(String value) {
        settings.save(new AppSetting(KEY, value));
    }

    private CommentSource parse(String value) {
        try {
            return CommentSource.valueOf(value);
        } catch (IllegalArgumentException e) {
            return CommentSource.ACTOR;
        }
    }
}

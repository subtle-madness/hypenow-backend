package com.celfit.crawler.settings.application.port.out;

import com.celfit.crawler.settings.domain.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}

package com.celfit.crawler.settings.application.port.out;

import com.celfit.crawler.crawling.domain.*;
import com.celfit.crawler.content.domain.*;
import com.celfit.crawler.settings.domain.*;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}

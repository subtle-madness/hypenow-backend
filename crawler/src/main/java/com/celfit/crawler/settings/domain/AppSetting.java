package com.celfit.crawler.settings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "app_setting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppSetting {

    @Id
    @Column(nullable = false)
    private String key;

    @Setter
    @Column(nullable = false)
    private String value;

    public AppSetting(String key, String value) {
        this.key = key;
        this.value = value;
    }
}

package com.celfit.monitoring.config;

import com.celfit.monitoring.hiker.HikerProperties;
import com.celfit.monitoring.hiker.InstagramProxyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({HikerProperties.class, InstagramProxyProperties.class})
public class PropertiesConfig {}

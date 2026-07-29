package com.celfit.monitoring.config;

import com.celfit.monitoring.hiker.HikerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(HikerProperties.class)
public class PropertiesConfig {}

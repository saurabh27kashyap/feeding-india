package com.mealledger.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs. Switched off in tests, which drive time and jobs by hand. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mealledger.scheduling-enabled", havingValue = "true")
public class SchedulingConfig {
}

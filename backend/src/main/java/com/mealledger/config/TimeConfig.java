package com.mealledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {

    @Bean
    public AppClock appClock() {
        return AppClock.systemUtc();
    }
}

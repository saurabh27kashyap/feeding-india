package com.mealledger;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MealLedgerApplication {

    public static void main(String[] args) {
        // The server always runs in UTC, whatever machine it is on. Business times use the
        // configured zone explicitly (BusinessTime). This also stops the JDBC driver sending a
        // legacy zone name like "Asia/Calcutta", which Postgres 17 rejects.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(MealLedgerApplication.class, args);
    }
}

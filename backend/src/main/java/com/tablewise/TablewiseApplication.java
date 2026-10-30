package com.tablewise;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
@EnableJpaRepositories(considerNestedRepositories = true)
public class TablewiseApplication {
    public static void main(String[] args) {
        SpringApplication.run(TablewiseApplication.class, args);
    }
}

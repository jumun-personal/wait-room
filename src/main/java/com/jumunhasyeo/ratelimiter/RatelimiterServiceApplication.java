package com.jumunhasyeo.ratelimiter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@Slf4j
public class RatelimiterServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RatelimiterServiceApplication.class, args);
    }
}

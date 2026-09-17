package com.coldchainos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class ColdChainOsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ColdChainOsApplication.class, args);
    }
}

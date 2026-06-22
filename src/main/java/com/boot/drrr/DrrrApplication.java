package com.boot.drrr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DrrrApplication {

    public static void main(String[] args) {
        SpringApplication.run(DrrrApplication.class, args);
    }

}

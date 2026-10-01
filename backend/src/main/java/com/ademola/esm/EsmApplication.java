package com.ademola.esm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan // binds our typed @ConfigurationProperties records
public class EsmApplication {

    public static void main(String[] args) {
        SpringApplication.run(EsmApplication.class, args);
    }
}

package com.droplink;

import com.droplink.config.DropLinkProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(DropLinkProperties.class)
@EnableScheduling
public class DropLinkApplication {

    public static void main(String[] args) {
        SpringApplication.run(DropLinkApplication.class, args);
    }
}

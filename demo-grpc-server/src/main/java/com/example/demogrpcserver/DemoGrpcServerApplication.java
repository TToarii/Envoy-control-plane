package com.example.demogrpcserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

import javax.annotation.PostConstruct;
import java.util.TimeZone;

@EnableAsync
@SpringBootApplication
public class DemoGrpcServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoGrpcServerApplication.class, args);
    }

    @PostConstruct
    public void setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

}

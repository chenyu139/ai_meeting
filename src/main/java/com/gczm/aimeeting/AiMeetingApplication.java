package com.gczm.aimeeting;

import com.gczm.aimeeting.config.AppProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.gczm.aimeeting.mapper")
@EnableConfigurationProperties(AppProperties.class)
public class AiMeetingApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiMeetingApplication.class, args);
    }
}

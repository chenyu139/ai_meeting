package com.gczm.aimeeting.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI aiMeetingOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("AI Meeting Backend API")
                .description("AI听会后端接口文档（Spring Boot）")
                .version("v1")
                .contact(new Contact().name("GCZM Backend"))
                .license(new License().name("Internal Use")));
    }
}

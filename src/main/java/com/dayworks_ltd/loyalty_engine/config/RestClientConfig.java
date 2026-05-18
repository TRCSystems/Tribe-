package com.dayworks_ltd.loyalty_engine.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient restClient() {
        return RestClient.builder()
                .baseUrl("https://sbx.kra.go.ke")   // You can override this with @Value if needed
                .build();
    }
}
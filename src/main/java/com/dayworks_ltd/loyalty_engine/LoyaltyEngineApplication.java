package com.dayworks_ltd.loyalty_engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LoyaltyEngineApplication {

	public static void main(String[] args) {
		SpringApplication.run(LoyaltyEngineApplication.class, args);
	}

}



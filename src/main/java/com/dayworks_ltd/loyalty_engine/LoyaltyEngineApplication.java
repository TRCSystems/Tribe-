package com.dayworks_ltd.loyalty_engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class LoyaltyEngineApplication {

	/** The shops are in Kenya. The server runs on Europe/Berlin time, which put sale dates, the midnight
	 *  rollover and scheduled jobs 1-2 hours behind. Override with -Dapp.timezone=... if ever needed. */
	static final String DEFAULT_TIMEZONE = "Africa/Nairobi";

	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone(System.getProperty("app.timezone", DEFAULT_TIMEZONE)));
		SpringApplication.run(LoyaltyEngineApplication.class, args);
	}

}

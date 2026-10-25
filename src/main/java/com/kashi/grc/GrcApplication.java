package com.kashi.grc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class GrcApplication {

	public static void main(String[] args) {
		// Every timestamp is a LocalDateTime written with LocalDateTime.now() —
		// the JVM's wall clock — and sent to the browser as UTC
		// (app.time-zone=UTC, JacksonTimeConfig), which then shows it in the
		// viewer's zone. On a JVM that is not UTC (a laptop in India) the stored
		// clock is IST, is labelled UTC on the wire, and every time on screen ran
		// 5:30 ahead. Pinning the JVM to UTC, before anything reads the clock,
		// makes what is stored and what it is labelled agree everywhere — the
		// same as a UTC server already does.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(GrcApplication.class, args);
	}

}
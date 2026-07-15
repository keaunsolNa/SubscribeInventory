package com.dashboard.subscription.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web wiring: permissive read-only CORS so a browser artifact on any origin can read the dashboard,
 * plus sane connect/read timeouts on every outbound {@link RestClient}.
 */
@Configuration
public class WebConfig {

	private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
	private static final int READ_TIMEOUT_MILLIS = 10_000;

	@Bean
	public WebMvcConfigurer corsConfigurer() {
		return new WebMvcConfigurer() {

			@Override
			public void addCorsMappings(CorsRegistry registry) {
				registry.addMapping("/api/**")
						.allowedOrigins("*")
						.allowedMethods("GET", "POST")
						.allowedHeaders("*");
			}
		};
	}

	@Bean
	public RestClientCustomizer restClientCustomizer() {
		return builder -> {
			SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
			factory.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
			factory.setReadTimeout(READ_TIMEOUT_MILLIS);
			builder.requestFactory(factory);
		};
	}
}

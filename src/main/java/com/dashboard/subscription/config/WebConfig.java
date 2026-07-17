package com.dashboard.subscription.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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
	// OpenAI's /v1/organization/costs aggregates billing data and can exceed 10s; the 60s cache
	// keeps slow upstream calls to at most one per minute per credential set.
	private static final int READ_TIMEOUT_MILLIS = 20_000;

	@Bean
	public WebMvcConfigurer corsConfigurer() {
		return new WebMvcConfigurer() {

			@Override
			public void addCorsMappings(CorsRegistry registry) {
				registry.addMapping("/api/**")
						.allowedOrigins("*")
						.allowedMethods("GET", "POST", "DELETE")
						.allowedHeaders("*");
			}
		};
	}

	@Bean
	public RestClientCustomizer restClientCustomizer() {
		// JDK HttpClient (not HttpURLConnection) so PATCH is supported — Firestore updates need it.
		return builder -> {
			HttpClient httpClient = HttpClient.newBuilder()
					.connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MILLIS))
					.build();
			JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
			factory.setReadTimeout(Duration.ofMillis(READ_TIMEOUT_MILLIS));
			builder.requestFactory(factory);
		};
	}
}

package com.dashboard.subscription.provider;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.dashboard.subscription.config.ProviderProperties;
import com.dashboard.subscription.config.ProviderProperties.Config;
import com.dashboard.subscription.domain.MetricType;
import com.dashboard.subscription.domain.ProviderStatus;
import com.dashboard.subscription.domain.ProviderUsage;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * OpenRouter account credits via GET /api/v1/credits (bearer auth). Response
 * {@code data.total_credits} minus {@code data.total_usage} is the spendable remainder in USD.
 */
@Component
public class OpenRouterProvider extends AbstractUsageProvider {

	static final String PROVIDER_ID = "openrouter";
	private static final String CREDITS_PATH = "/api/v1/credits";

	public OpenRouterProvider(ProviderProperties properties, RestClient.Builder restClientBuilder) {
		super(properties, restClientBuilder);
	}

	@Override
	public String providerId() {
		return PROVIDER_ID;
	}

	@Override
	protected String displayName() {
		return "OpenRouter";
	}

	@Override
	protected MetricType metricType() {
		return MetricType.BALANCE;
	}

	@Override
	protected ProviderUsage fetchActive(Config config) {
		JsonNode body = client(config).get()
				.uri(CREDITS_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
				.retrieve()
				.body(JsonNode.class);
		return parse(body);
	}

	ProviderUsage parse(JsonNode body) {
		JsonNode data = body.path("data");
		double totalCredits = data.path("total_credits").asDouble();
		double totalUsage = data.path("total_usage").asDouble();
		return base()
				.status(ProviderStatus.OK)
				.plan("Credits")
				.unit("USD")
				.limit(totalCredits)
				.used(totalUsage)
				.remaining(totalCredits - totalUsage)
				.currency("USD")
				.build();
	}
}

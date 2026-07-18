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
 * Stability AI credit balance via GET /v1/user/balance (bearer auth); the response is a single
 * {@code credits} number (Stability's own credit unit, not USD).
 */
@Component
public class StabilityProvider extends AbstractUsageProvider {

	static final String PROVIDER_ID = "stability";
	private static final String BALANCE_PATH = "/v1/user/balance";

	public StabilityProvider(ProviderProperties properties, RestClient.Builder restClientBuilder) {
		super(properties, restClientBuilder);
	}

	@Override
	public String providerId() {
		return PROVIDER_ID;
	}

	@Override
	protected String displayName() {
		return "Stability AI";
	}

	@Override
	protected MetricType metricType() {
		return MetricType.BALANCE;
	}

	@Override
	protected ProviderUsage fetchActive(Config config) {
		JsonNode body = client(config).get()
				.uri(BALANCE_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
				.retrieve()
				.body(JsonNode.class);
		return parse(body);
	}

	ProviderUsage parse(JsonNode body) {
		return base()
				.status(ProviderStatus.OK)
				.plan("Credits")
				.unit("credits")
				.remaining(body.path("credits").asDouble())
				.currency("credits")
				.build();
	}
}

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
 * fal.ai account billing via GET /v1/account/billing?expand=credits ("Key" auth scheme).
 * The expanded {@code credits} object carries the current credit balance in USD.
 */
@Component
public class FalProvider extends AbstractUsageProvider {

	static final String PROVIDER_ID = "fal";
	private static final String BILLING_PATH = "/v1/account/billing?expand=credits";

	public FalProvider(ProviderProperties properties, RestClient.Builder restClientBuilder) {
		super(properties, restClientBuilder);
	}

	@Override
	public String providerId() {
		return PROVIDER_ID;
	}

	@Override
	protected String displayName() {
		return "fal.ai";
	}

	@Override
	protected MetricType metricType() {
		return MetricType.BALANCE;
	}

	@Override
	protected ProviderUsage fetchActive(Config config) {
		JsonNode body = client(config).get()
				.uri(BILLING_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Key " + config.getApiKey())
				.retrieve()
				.body(JsonNode.class);
		return parse(body);
	}

	ProviderUsage parse(JsonNode body) {
		JsonNode credits = body.path("credits");
		double balance = credits.isNumber()
				? credits.asDouble() : credits.path("balance").asDouble();
		return base()
				.status(ProviderStatus.OK)
				.plan("Credits")
				.unit("USD")
				.remaining(balance)
				.currency("USD")
				.build();
	}
}

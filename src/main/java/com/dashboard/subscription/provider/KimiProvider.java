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
 * Kimi (Moonshot AI) prepaid balance via GET /v1/users/me/balance (bearer auth).
 *
 * <p>The response splits the balance three ways: {@code cash_balance} is money paid in and can go
 * negative into debt, {@code voucher_balance} is promotional credit, and {@code available_balance}
 * is what the two actually buy — Moonshot refuses calls once it reaches zero. Only the last one
 * answers "how much is left", so that is what the card reports.
 *
 * <p>Keys are issued separately by platform.kimi.ai and platform.kimi.com and are not
 * interchangeable: a key from one returns 401 on the other, which reads as a bad key rather than a
 * wrong host. The base URL is configurable so a .com account can point at its own origin.
 */
@Component
public class KimiProvider extends AbstractUsageProvider {

	static final String PROVIDER_ID = "kimi";
	private static final String BALANCE_PATH = "/v1/users/me/balance";

	public KimiProvider(ProviderProperties properties, RestClient.Builder restClientBuilder) {
		super(properties, restClientBuilder);
	}

	@Override
	public String providerId() {
		return PROVIDER_ID;
	}

	@Override
	protected String displayName() {
		return "Kimi (Moonshot)";
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
		JsonNode data = body.path("data");
		return base()
				.status(ProviderStatus.OK)
				.plan("Prepaid")
				.unit("USD")
				.remaining(data.path("available_balance").asDouble())
				.currency("USD")
				.build();
	}
}

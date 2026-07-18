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
 * DeepSeek prepaid balance via GET /user/balance (bearer auth). Response carries
 * {@code balance_infos[]} with decimal-string amounts per currency; the first entry's
 * {@code total_balance} is the spendable remainder.
 */
@Component
public class DeepSeekProvider extends AbstractUsageProvider {

	static final String PROVIDER_ID = "deepseek";
	private static final String BALANCE_PATH = "/user/balance";

	public DeepSeekProvider(ProviderProperties properties, RestClient.Builder restClientBuilder) {
		super(properties, restClientBuilder);
	}

	@Override
	public String providerId() {
		return PROVIDER_ID;
	}

	@Override
	protected String displayName() {
		return "DeepSeek";
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
		JsonNode info = body.path("balance_infos").path(0);
		return base()
				.status(ProviderStatus.OK)
				.plan("Prepaid")
				.unit(info.path("currency").asText("USD"))
				.remaining(info.path("total_balance").asDouble())
				.currency(info.path("currency").asText("USD"))
				.build();
	}
}

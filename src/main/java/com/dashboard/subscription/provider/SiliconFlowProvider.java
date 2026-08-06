package com.dashboard.subscription.provider;

import java.net.URI;

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
 * SiliconFlow prepaid balance via GET /v1/user/info (bearer auth).
 *
 * <p>The payload splits the wallet into {@code balance} (granted credit) and {@code chargeBalance}
 * (money paid in), with {@code totalBalance} as the sum. Only the sum is spendable in practice, so
 * that is what the card reports — the same reason Kimi reports its available figure rather than its
 * cash figure. Amounts arrive as decimal strings.
 *
 * <p>The response carries no currency. SiliconFlow runs two separate account systems on two hosts,
 * and they bill in different money: the China site (siliconflow.cn) in CNY, the global site
 * (siliconflow.com) in USD. There is nothing in the body to distinguish them, so the currency is
 * taken from the configured host — the only signal that actually correlates. Getting this wrong
 * would be worse than leaving it blank: the dashboard sums USD spend, and a CNY figure quietly
 * added to that total would misstate it.
 */
@Component
public class SiliconFlowProvider extends AbstractUsageProvider {

	static final String PROVIDER_ID = "siliconflow";
	private static final String USER_INFO_PATH = "/v1/user/info";

	public SiliconFlowProvider(ProviderProperties properties, RestClient.Builder restClientBuilder) {
		super(properties, restClientBuilder);
	}

	@Override
	public String providerId() {
		return PROVIDER_ID;
	}

	@Override
	protected String displayName() {
		return "SiliconFlow";
	}

	@Override
	protected MetricType metricType() {
		return MetricType.BALANCE;
	}

	@Override
	protected ProviderUsage fetchActive(Config config) {
		JsonNode body = client(config).get()
				.uri(USER_INFO_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getApiKey())
				.retrieve()
				.body(JsonNode.class);
		return parse(body, currencyFor(config.getBaseUrl()));
	}

	/** China host bills in CNY; every other host (including the global one) in USD. */
	static String currencyFor(String baseUrl) {
		if (baseUrl == null) {
			return "USD";
		}
		try {
			String host = URI.create(baseUrl).getHost();
			return host != null && host.toLowerCase().endsWith(".cn") ? "CNY" : "USD";
		} catch (IllegalArgumentException exception) {
			return "USD";
		}
	}

	ProviderUsage parse(JsonNode body, String currency) {
		JsonNode data = body.path("data");
		return base()
				.status(ProviderStatus.OK)
				.plan("Prepaid")
				.unit(currency)
				.remaining(data.path("totalBalance").asDouble())
				.currency(currency)
				.build();
	}
}

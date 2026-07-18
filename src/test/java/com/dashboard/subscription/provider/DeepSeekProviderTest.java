package com.dashboard.subscription.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.dashboard.subscription.config.ProviderProperties;
import com.dashboard.subscription.config.ProviderProperties.Config;
import com.dashboard.subscription.domain.MetricType;
import com.dashboard.subscription.domain.ProviderStatus;
import com.dashboard.subscription.domain.ProviderUsage;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

class DeepSeekProviderTest {

	private MockWebServer server;

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	@Test
	void fetchReadsTotalBalanceFromFirstBalanceInfo() throws InterruptedException {
		String json = """
				{
				  "is_available": true,
				  "balance_infos": [
				    { "currency": "USD", "total_balance": "23.50",
				      "granted_balance": "0.00", "topped_up_balance": "23.50" }
				  ]
				}
				""";
		server.enqueue(new MockResponse().setBody(json)
				.addHeader("Content-Type", "application/json"));

		ProviderUsage usage = provider("sk-deepseek").fetch();

		assertThat(usage.getProviderId()).isEqualTo("deepseek");
		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.OK);
		assertThat(usage.getMetricType()).isEqualTo(MetricType.BALANCE);
		assertThat(usage.getRemaining()).isEqualTo(23.50d);
		assertThat(usage.getCurrency()).isEqualTo("USD");

		RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
		assertThat(recorded.getPath()).isEqualTo("/user/balance");
		assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-deepseek");
	}

	@Test
	void disabledWithoutKey() {
		ProviderUsage usage = provider("").fetch();

		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.DISABLED);
		assertThat(server.getRequestCount()).isZero();
	}

	private DeepSeekProvider provider(String apiKey) {
		Config config = new Config();
		config.setEnabled(true);
		config.setBaseUrl(server.url("/").toString());
		config.setApiKey(apiKey);

		ProviderProperties properties = new ProviderProperties();
		properties.setProviders(Map.of(DeepSeekProvider.PROVIDER_ID, config));
		return new DeepSeekProvider(properties, RestClient.builder());
	}
}

package com.dashboard.subscription.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

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

class AnthropicProviderTest {

	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-15T09:00:00Z"), ZoneOffset.UTC);

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
	void fetchSumsCostFromStringAmounts() throws InterruptedException {
		String json = """
				{
				  "data": [
				    {"results": [{"amount": "10.50", "currency": "USD"}]},
				    {"results": [{"amount": "4.50", "currency": "USD"}]}
				  ]
				}
				""";
		server.enqueue(new MockResponse()
				.setBody(json)
				.addHeader("Content-Type", "application/json"));

		ProviderUsage usage = provider("admin-key").fetch();

		assertThat(usage.getProviderId()).isEqualTo("anthropic");
		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.OK);
		assertThat(usage.getMetricType()).isEqualTo(MetricType.COST);
		assertThat(usage.getCost()).isEqualTo(15.0d);
		assertThat(usage.getCurrency()).isEqualTo("USD");

		RecordedRequest recorded = server.takeRequest();
		assertThat(recorded.getPath()).startsWith("/v1/organizations/cost_report");
		assertThat(recorded.getPath()).contains("starting_at=2026-07-01");
		assertThat(recorded.getHeader("x-api-key")).isEqualTo("admin-key");
		assertThat(recorded.getHeader("anthropic-version")).isEqualTo("2023-06-01");
	}

	private AnthropicProvider provider(String apiKey) {
		Config config = new Config();
		config.setEnabled(true);
		config.setBaseUrl(server.url("/").toString());
		config.setApiKey(apiKey);

		ProviderProperties properties = new ProviderProperties();
		properties.setProviders(Map.of(AnthropicProvider.PROVIDER_ID, config));
		return new AnthropicProvider(properties, RestClient.builder(), FIXED_CLOCK);
	}
}

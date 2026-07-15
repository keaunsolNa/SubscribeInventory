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

class OpenAiProviderTest {

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
	void fetchSumsCostAcrossBuckets() throws InterruptedException {
		String json = """
				{
				  "object": "page",
				  "data": [
				    {"results": [{"amount": {"value": 1.25, "currency": "usd"}}]},
				    {"results": [{"amount": {"value": 2.50, "currency": "usd"}},
				                 {"amount": {"value": 0.25, "currency": "usd"}}]}
				  ]
				}
				""";
		server.enqueue(new MockResponse()
				.setBody(json)
				.addHeader("Content-Type", "application/json"));

		ProviderUsage usage = provider("admin-key").fetch();

		assertThat(usage.getProviderId()).isEqualTo("openai");
		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.OK);
		assertThat(usage.getMetricType()).isEqualTo(MetricType.COST);
		assertThat(usage.getCost()).isEqualTo(4.0d);
		assertThat(usage.getCurrency()).isEqualTo("USD");

		RecordedRequest recorded = server.takeRequest();
		assertThat(recorded.getPath()).startsWith("/v1/organization/costs");
		assertThat(recorded.getPath()).contains("start_time=1782864000");
		assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer admin-key");
	}

	@Test
	void costIsZeroWhenNoBuckets() {
		server.enqueue(new MockResponse()
				.setBody("{\"data\": []}")
				.addHeader("Content-Type", "application/json"));

		ProviderUsage usage = provider("admin-key").fetch();

		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.OK);
		assertThat(usage.getCost()).isEqualTo(0.0d);
	}

	private OpenAiProvider provider(String apiKey) {
		Config config = new Config();
		config.setEnabled(true);
		config.setBaseUrl(server.url("/").toString());
		config.setApiKey(apiKey);

		ProviderProperties properties = new ProviderProperties();
		properties.setProviders(Map.of(OpenAiProvider.PROVIDER_ID, config));
		return new OpenAiProvider(properties, RestClient.builder(), FIXED_CLOCK);
	}
}

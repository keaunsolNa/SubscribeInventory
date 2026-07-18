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

class StabilityProviderTest {

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
	void fetchReadsCreditBalance() throws InterruptedException {
		server.enqueue(new MockResponse().setBody("{ \"credits\": 512.35 }")
				.addHeader("Content-Type", "application/json"));

		ProviderUsage usage = provider("sk-stability").fetch();

		assertThat(usage.getProviderId()).isEqualTo("stability");
		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.OK);
		assertThat(usage.getMetricType()).isEqualTo(MetricType.BALANCE);
		assertThat(usage.getRemaining()).isEqualTo(512.35d);
		assertThat(usage.getUnit()).isEqualTo("credits");

		RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
		assertThat(recorded.getPath()).isEqualTo("/v1/user/balance");
		assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-stability");
	}

	@Test
	void disabledWithoutKey() {
		ProviderUsage usage = provider("").fetch();

		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.DISABLED);
		assertThat(server.getRequestCount()).isZero();
	}

	private StabilityProvider provider(String apiKey) {
		Config config = new Config();
		config.setEnabled(true);
		config.setBaseUrl(server.url("/").toString());
		config.setApiKey(apiKey);

		ProviderProperties properties = new ProviderProperties();
		properties.setProviders(Map.of(StabilityProvider.PROVIDER_ID, config));
		return new StabilityProvider(properties, RestClient.builder());
	}
}

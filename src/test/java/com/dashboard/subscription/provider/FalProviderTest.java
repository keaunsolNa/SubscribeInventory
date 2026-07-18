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

class FalProviderTest {

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
	void fetchReadsExpandedCreditBalance() throws InterruptedException {
		server.enqueue(new MockResponse()
				.setBody("{ \"credits\": { \"balance\": 41.2 } }")
				.addHeader("Content-Type", "application/json"));

		ProviderUsage usage = provider("fal-key").fetch();

		assertThat(usage.getProviderId()).isEqualTo("fal");
		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.OK);
		assertThat(usage.getMetricType()).isEqualTo(MetricType.BALANCE);
		assertThat(usage.getRemaining()).isEqualTo(41.2d);

		RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
		assertThat(recorded.getPath()).isEqualTo("/v1/account/billing?expand=credits");
		assertThat(recorded.getHeader("Authorization")).isEqualTo("Key fal-key");
	}

	@Test
	void fetchAcceptsPlainNumberCredits() {
		server.enqueue(new MockResponse().setBody("{ \"credits\": 12.0 }")
				.addHeader("Content-Type", "application/json"));

		ProviderUsage usage = provider("fal-key").fetch();

		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.OK);
		assertThat(usage.getRemaining()).isEqualTo(12.0d);
	}

	@Test
	void disabledWithoutKey() {
		ProviderUsage usage = provider("").fetch();

		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.DISABLED);
		assertThat(server.getRequestCount()).isZero();
	}

	private FalProvider provider(String apiKey) {
		Config config = new Config();
		config.setEnabled(true);
		config.setBaseUrl(server.url("/").toString());
		config.setApiKey(apiKey);

		ProviderProperties properties = new ProviderProperties();
		properties.setProviders(Map.of(FalProvider.PROVIDER_ID, config));
		return new FalProvider(properties, RestClient.builder());
	}
}

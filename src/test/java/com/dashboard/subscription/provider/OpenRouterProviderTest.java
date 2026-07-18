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

class OpenRouterProviderTest {

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
	void fetchComputesRemainingFromCredits() throws InterruptedException {
		String json = """
				{ "data": { "total_credits": 25.0, "total_usage": 6.5 } }
				""";
		server.enqueue(new MockResponse().setBody(json)
				.addHeader("Content-Type", "application/json"));

		ProviderUsage usage = provider("sk-or-key").fetch();

		assertThat(usage.getProviderId()).isEqualTo("openrouter");
		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.OK);
		assertThat(usage.getMetricType()).isEqualTo(MetricType.BALANCE);
		assertThat(usage.getLimit()).isEqualTo(25.0d);
		assertThat(usage.getUsed()).isEqualTo(6.5d);
		assertThat(usage.getRemaining()).isEqualTo(18.5d);

		RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
		assertThat(recorded.getPath()).isEqualTo("/api/v1/credits");
		assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-or-key");
	}

	@Test
	void disabledWithoutKey() {
		ProviderUsage usage = provider("").fetch();

		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.DISABLED);
		assertThat(server.getRequestCount()).isZero();
	}

	private OpenRouterProvider provider(String apiKey) {
		Config config = new Config();
		config.setEnabled(true);
		config.setBaseUrl(server.url("/").toString());
		config.setApiKey(apiKey);

		ProviderProperties properties = new ProviderProperties();
		properties.setProviders(Map.of(OpenRouterProvider.PROVIDER_ID, config));
		return new OpenRouterProvider(properties, RestClient.builder());
	}
}

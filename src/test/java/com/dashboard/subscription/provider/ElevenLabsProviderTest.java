package com.dashboard.subscription.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
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

class ElevenLabsProviderTest {

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
	void fetchMapsCharacterQuotaAndRemaining() throws InterruptedException {
		String json = """
				{
				  "tier": "creator",
				  "character_count": 12000,
				  "character_limit": 100000,
				  "next_character_count_reset_unix": 1753000000,
				  "currency": "usd",
				  "status": "active"
				}
				""";
		server.enqueue(new MockResponse()
				.setBody(json)
				.addHeader("Content-Type", "application/json"));

		ProviderUsage usage = provider("secret-key").fetch();

		assertThat(usage.getProviderId()).isEqualTo("elevenlabs");
		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.OK);
		assertThat(usage.getMetricType()).isEqualTo(MetricType.QUOTA);
		assertThat(usage.getPlan()).isEqualTo("creator");
		assertThat(usage.getUsed()).isEqualTo(12000.0d);
		assertThat(usage.getLimit()).isEqualTo(100000.0d);
		assertThat(usage.getRemaining()).isEqualTo(88000.0d);
		assertThat(usage.getUsedPercent()).isEqualTo(12.0d);
		assertThat(usage.getResetsAt()).isEqualTo(Instant.ofEpochSecond(1753000000L));

		RecordedRequest recorded = server.takeRequest();
		assertThat(recorded.getPath()).isEqualTo("/v1/user/subscription");
		assertThat(recorded.getHeader("xi-api-key")).isEqualTo("secret-key");
	}

	@Test
	void disabledWhenApiKeyBlankAndMakesNoCall() {
		ProviderUsage usage = provider("").fetch();

		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.DISABLED);
		assertThat(usage.getRemaining()).isNull();
		assertThat(server.getRequestCount()).isZero();
	}

	@Test
	void errorWhenProviderReturnsUnauthorized() {
		server.enqueue(new MockResponse().setResponseCode(401));

		ProviderUsage usage = provider("bad-key").fetch();

		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.ERROR);
		assertThat(usage.getMessage()).isNotBlank();
	}

	private ElevenLabsProvider provider(String apiKey) {
		Config config = new Config();
		config.setEnabled(true);
		config.setBaseUrl(server.url("/").toString());
		config.setApiKey(apiKey);

		ProviderProperties properties = new ProviderProperties();
		properties.setProviders(Map.of(ElevenLabsProvider.PROVIDER_ID, config));
		return new ElevenLabsProvider(properties, RestClient.builder());
	}
}

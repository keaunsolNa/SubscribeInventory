package com.dashboard.subscription.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.dashboard.subscription.config.ProviderProperties;
import com.dashboard.subscription.config.ProviderProperties.Config;
import com.dashboard.subscription.domain.ApiCredentials;
import com.dashboard.subscription.domain.MetricType;
import com.dashboard.subscription.domain.ProviderStatus;
import com.dashboard.subscription.domain.ProviderUsage;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

class XaiProviderTest {

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
	void fetchComputesRemainingFromPrepaidCreditFields() throws InterruptedException {
		// Real schema (verified 2026-07-15): monetary values are objects holding a "val"
		// string in USD cents; purchases and spend are both negative.
		String json = """
				{
				  "coreInvoice": {
				    "prepaidCredits": { "val": "-500" },
				    "prepaidCreditsUsed": { "val": "-104" }
				  },
				  "billingCycle": { "year": 2026, "month": 7 }
				}
				""";
		server.enqueue(new MockResponse()
				.setBody(json)
				.addHeader("Content-Type", "application/json"));

		ProviderUsage usage = provider("mgmt-key", "team_abc").fetch();

		assertThat(usage.getProviderId()).isEqualTo("xai");
		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.OK);
		assertThat(usage.getMetricType()).isEqualTo(MetricType.BALANCE);
		assertThat(usage.getLimit()).isEqualTo(5.0d);
		assertThat(usage.getUsed()).isEqualTo(1.04d);
		assertThat(usage.getRemaining()).isEqualTo(3.96d);
		assertThat(usage.getCurrency()).isEqualTo("USD");
		assertThat(usage.getMonthlyFee()).isEqualTo(22000L);

		RecordedRequest recorded = server.takeRequest();
		assertThat(recorded.getPath()).isEqualTo("/v1/billing/teams/team_abc/postpaid/invoice/preview");
		assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer mgmt-key");
	}

	@Test
	void fetchUsesRequestCredentialsWhenServerHasNoKey() throws InterruptedException {
		String json = """
				{
				  "coreInvoice": {
				    "prepaidCredits": { "val": "-500" },
				    "prepaidCreditsUsed": { "val": "-104" }
				  }
				}
				""";
		server.enqueue(new MockResponse()
				.setBody(json)
				.addHeader("Content-Type", "application/json"));

		ProviderUsage usage = provider("", "")
				.fetch(new ApiCredentials("byok-key", "team_byok"));

		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.OK);
		assertThat(usage.getRemaining()).isEqualTo(3.96d);

		RecordedRequest recorded = server.takeRequest();
		assertThat(recorded.getPath()).isEqualTo("/v1/billing/teams/team_byok/postpaid/invoice/preview");
		assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer byok-key");
	}

	@Test
	void disabledWhenApiKeyBlank() {
		ProviderUsage usage = provider("", "team_abc").fetch();

		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.DISABLED);
		assertThat(usage.getMonthlyFee()).isEqualTo(22000L);
		assertThat(server.getRequestCount()).isZero();
	}

	private XaiProvider provider(String apiKey, String teamId) {
		Config config = new Config();
		config.setEnabled(true);
		config.setBaseUrl(server.url("/").toString());
		config.setApiKey(apiKey);
		config.setTeamId(teamId);
		config.setMonthlyFee(22000L);

		ProviderProperties properties = new ProviderProperties();
		properties.setProviders(Map.of(XaiProvider.PROVIDER_ID, config));
		return new XaiProvider(properties, RestClient.builder());
	}
}

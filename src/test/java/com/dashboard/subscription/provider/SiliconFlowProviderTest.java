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

class SiliconFlowProviderTest {

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

	/** Amounts arrive as decimal strings, and the spendable figure is the sum, not either half. */
	@Test
	void fetchReadsTotalBalanceFromDecimalStrings() throws InterruptedException {
		String json = """
				{
				  "code": 20000,
				  "message": "OK",
				  "status": true,
				  "data": {
				    "id": "user-1",
				    "name": "tester",
				    "email": "tester@example.com",
				    "isAdmin": false,
				    "balance": "0.88",
				    "chargeBalance": "88.00",
				    "totalBalance": "88.88"
				  }
				}
				""";
		server.enqueue(new MockResponse().setBody(json)
				.addHeader("Content-Type", "application/json"));

		ProviderUsage usage = provider("sk-sf", server.url("/").toString()).fetch();

		assertThat(usage.getProviderId()).isEqualTo("siliconflow");
		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.OK);
		assertThat(usage.getMetricType()).isEqualTo(MetricType.BALANCE);
		assertThat(usage.getRemaining()).isEqualTo(88.88d);

		RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
		assertThat(recorded.getPath()).isEqualTo("/v1/user/info");
		assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-sf");
	}

	/**
	 * The body carries no currency and the two SiliconFlow sites bill in different money. Reading it
	 * off the host is the only signal available, and getting it wrong would fold a CNY figure into
	 * the dashboard's USD spend total.
	 */
	@Test
	void currencyFollowsTheHost() {
		assertThat(SiliconFlowProvider.currencyFor("https://api.siliconflow.cn")).isEqualTo("CNY");
		assertThat(SiliconFlowProvider.currencyFor("https://api.siliconflow.cn/")).isEqualTo("CNY");
		assertThat(SiliconFlowProvider.currencyFor("https://api.siliconflow.com")).isEqualTo("USD");
		assertThat(SiliconFlowProvider.currencyFor("https://api.siliconflow.com/v1")).isEqualTo("USD");
		assertThat(SiliconFlowProvider.currencyFor(null)).isEqualTo("USD");
		assertThat(SiliconFlowProvider.currencyFor("not a url")).isEqualTo("USD");
	}

	@Test
	void chinaHostReportsCny() {
		String json = """
				{"code":20000,"status":true,"data":{"balance":"0.00","chargeBalance":"14.00","totalBalance":"14.00"}}
				""";
		server.enqueue(new MockResponse().setBody(json)
				.addHeader("Content-Type", "application/json"));

		ProviderUsage usage = provider("sk-sf", "https://api.siliconflow.cn").parse(
				jsonOf(json), SiliconFlowProvider.currencyFor("https://api.siliconflow.cn"));

		assertThat(usage.getCurrency()).isEqualTo("CNY");
		assertThat(usage.getRemaining()).isEqualTo(14.00d);
	}

	@Test
	void exhaustedAccountReportsZero() {
		String json = """
				{"code":20000,"status":true,"data":{"balance":"0.00","chargeBalance":"0.00","totalBalance":"0.00"}}
				""";
		server.enqueue(new MockResponse().setBody(json)
				.addHeader("Content-Type", "application/json"));

		ProviderUsage usage = provider("sk-sf", server.url("/").toString()).fetch();

		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.OK);
		assertThat(usage.getRemaining()).isZero();
	}

	@Test
	void rejectedKeySurfacesAsError() {
		server.enqueue(new MockResponse().setResponseCode(401)
				.setBody("{\"code\":20001,\"message\":\"invalid token\",\"status\":false}")
				.addHeader("Content-Type", "application/json"));

		ProviderUsage usage = provider("sk-bad", server.url("/").toString()).fetch();

		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.ERROR);
		assertThat(usage.getMessage()).isNotBlank();
	}

	@Test
	void disabledWithoutKey() {
		ProviderUsage usage = provider("", server.url("/").toString()).fetch();

		assertThat(usage.getStatus()).isEqualTo(ProviderStatus.DISABLED);
		assertThat(server.getRequestCount()).isZero();
	}

	private com.fasterxml.jackson.databind.JsonNode jsonOf(String json) {
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private SiliconFlowProvider provider(String apiKey, String baseUrl) {
		Config config = new Config();
		config.setEnabled(true);
		config.setBaseUrl(baseUrl);
		config.setApiKey(apiKey);

		ProviderProperties properties = new ProviderProperties();
		properties.setProviders(Map.of(SiliconFlowProvider.PROVIDER_ID, config));
		return new SiliconFlowProvider(properties, RestClient.builder());
	}
}

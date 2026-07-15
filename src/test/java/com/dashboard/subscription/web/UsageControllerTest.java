package com.dashboard.subscription.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.dashboard.subscription.domain.ApiCredentials;
import com.dashboard.subscription.domain.MetricType;
import com.dashboard.subscription.domain.ProviderStatus;
import com.dashboard.subscription.domain.ProviderUsage;
import com.dashboard.subscription.service.CachedUsageService;

@WebMvcTest(UsageController.class)
class UsageControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private CachedUsageService cachedUsageService;

	@Test
	void usageEndpointReturnsAggregatedSnapshots() throws Exception {
		DashboardResponse response = DashboardResponse.builder()
				.generatedAt(Instant.parse("2026-07-15T09:00:00Z"))
				.providers(List.of(ProviderUsage.builder()
						.providerId("elevenlabs")
						.displayName("ElevenLabs")
						.metricType(MetricType.QUOTA)
						.status(ProviderStatus.OK)
						.used(12000.0d)
						.limit(100000.0d)
						.remaining(88000.0d)
						.build()))
				.build();
		when(cachedUsageService.collect(Map.of())).thenReturn(response);

		mockMvc.perform(get("/api/usage"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.generatedAt").value("2026-07-15T09:00:00Z"))
				.andExpect(jsonPath("$.providers[0].providerId").value("elevenlabs"))
				.andExpect(jsonPath("$.providers[0].usedPercent").value(12.0d))
				.andExpect(jsonPath("$.providers[0].remaining").value(88000.0d));
	}

	@Test
	void usagePostRelaysByokCredentialsToAggregator() throws Exception {
		DashboardResponse response = DashboardResponse.builder()
				.generatedAt(Instant.parse("2026-07-15T09:00:00Z"))
				.providers(List.of())
				.build();
		when(cachedUsageService.collect(Map.of("xai", new ApiCredentials("byok-key", "team_byok"))))
				.thenReturn(response);

		mockMvc.perform(post("/api/usage")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"keys\":{\"xai\":{\"apiKey\":\"byok-key\",\"teamId\":\"team_byok\"}}}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.generatedAt").value("2026-07-15T09:00:00Z"));
	}

	@Test
	void healthEndpointReportsUp() throws Exception {
		mockMvc.perform(get("/api/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}
}

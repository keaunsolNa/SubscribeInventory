package com.dashboard.subscription.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.dashboard.subscription.config.AuthProperties;
import com.dashboard.subscription.config.WebConfig;
import com.dashboard.subscription.service.BudgetAlertService;
import com.dashboard.subscription.service.CachedUsageService;
import com.dashboard.subscription.service.JwtService;
import com.dashboard.subscription.service.UsageHistoryStore;

/**
 * Auth behavior when only the shared ACCESS_TOKEN is configured (no Google login).
 */
@WebMvcTest({UsageController.class, BudgetController.class})
@Import(WebConfig.class)
@EnableConfigurationProperties(AuthProperties.class)
@TestPropertySource(properties = "dashboard.security.access-token=secret-token")
class AuthFilterLegacyModeTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private CachedUsageService cachedUsageService;

	@MockBean
	private UsageHistoryStore usageHistoryStore;

	@MockBean
	private BudgetAlertService budgetAlertService;

	@MockBean
	private JwtService jwtService;

	@Test
	void rejectsApiRequestWithoutToken() throws Exception {
		mockMvc.perform(get("/api/usage"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void acceptsApiRequestWithSharedToken() throws Exception {
		mockMvc.perform(get("/api/usage").header("X-Access-Token", "secret-token"))
				.andExpect(status().isOk());
	}

	@Test
	void budgetPushPassesWithQueryToken() throws Exception {
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.post("/api/budget/notify").param("token", "secret-token")
						.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
						.content("{\"message\":{\"data\":\"\"}}"))
				.andExpect(status().isOk());
	}

	@Test
	void budgetPushRejectedWithoutToken() throws Exception {
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.post("/api/budget/notify")
						.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void healthStaysOpen() throws Exception {
		mockMvc.perform(get("/api/health"))
				.andExpect(status().isOk());
	}

	@Test
	void corsPreflightStaysOpen() throws Exception {
		mockMvc.perform(options("/api/usage")
						.header("Origin", "https://example.com")
						.header("Access-Control-Request-Method", "POST"))
				.andExpect(status().isOk());
	}
}

package com.dashboard.subscription.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.dashboard.subscription.config.WebConfig;
import com.dashboard.subscription.service.CachedUsageService;

@WebMvcTest(UsageController.class)
@Import(WebConfig.class)
@TestPropertySource(properties = "dashboard.security.access-token=secret-token")
class AccessTokenFilterTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private CachedUsageService cachedUsageService;

	@Test
	void rejectsApiRequestWithoutToken() throws Exception {
		mockMvc.perform(get("/api/usage"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsApiRequestWithWrongToken() throws Exception {
		mockMvc.perform(get("/api/usage").header(AccessTokenFilter.TOKEN_HEADER, "wrong"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void acceptsApiRequestWithCorrectToken() throws Exception {
		mockMvc.perform(get("/api/usage").header(AccessTokenFilter.TOKEN_HEADER, "secret-token"))
				.andExpect(status().isOk());
	}

	@Test
	void healthStaysOpenForPlatformChecks() throws Exception {
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

	@Test
	void corsAllowsDeleteForUnsubscribe() throws Exception {
		mockMvc.perform(options("/api/alerts/subscriptions/abc")
						.header("Origin", "https://example.com")
						.header("Access-Control-Request-Method", "DELETE"))
				.andExpect(status().isOk());
	}
}

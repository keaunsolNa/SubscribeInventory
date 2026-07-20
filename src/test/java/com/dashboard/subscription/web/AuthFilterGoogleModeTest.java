package com.dashboard.subscription.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

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
import com.dashboard.subscription.domain.AuthUser;
import com.dashboard.subscription.service.AlertSubscriptionService;
import com.dashboard.subscription.service.CachedUsageService;
import com.dashboard.subscription.service.UsageHistoryStore;
import com.dashboard.subscription.service.JwtService;
import com.dashboard.subscription.service.SweepResult;

/**
 * Auth behavior once Google login is configured: user endpoints require a session JWT, while
 * the scheduler keeps using the shared token for the sweep trigger only.
 */
@WebMvcTest({UsageController.class, AlertController.class})
@Import(WebConfig.class)
@EnableConfigurationProperties(AuthProperties.class)
@TestPropertySource(properties = {
		"dashboard.security.access-token=secret-token",
		"dashboard.auth.google-client-id=client-1.apps.googleusercontent.com",
		"dashboard.auth.jwt-secret=c2VjcmV0LXNlY3JldC1zZWNyZXQtc2VjcmV0LXNlY3JldCE="
})
class AuthFilterGoogleModeTest {

	@Autowired
	private MockMvc mockMvc;

	@MockBean
	private CachedUsageService cachedUsageService;

	@MockBean
	private UsageHistoryStore usageHistoryStore;

	@MockBean
	private AlertSubscriptionService alertSubscriptionService;

	@MockBean
	private JwtService jwtService;

	@Test
	void usageRequiresLogin() throws Exception {
		mockMvc.perform(get("/api/usage"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void sharedTokenNoLongerGrantsUserAccess() throws Exception {
		mockMvc.perform(get("/api/usage").header("X-Access-Token", "secret-token"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void validSessionJwtGrantsAccess() throws Exception {
		when(jwtService.verify("good-jwt"))
				.thenReturn(Optional.of(new AuthUser("user-1", "user@example.com")));

		mockMvc.perform(get("/api/usage").header("Authorization", "Bearer good-jwt"))
				.andExpect(status().isOk());
	}

	@Test
	void invalidJwtRejected() throws Exception {
		when(jwtService.verify("bad-jwt")).thenReturn(Optional.empty());

		mockMvc.perform(get("/api/usage").header("Authorization", "Bearer bad-jwt"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void schedulerSweepStillUsesSharedToken() throws Exception {
		when(alertSubscriptionService.sweep()).thenReturn(new SweepResult(true, 0, 0));

		mockMvc.perform(post("/api/alerts/check").header("X-Access-Token", "secret-token"))
				.andExpect(status().isOk());
	}
}

package com.dashboard.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.dashboard.subscription.config.AuthProperties;
import com.dashboard.subscription.domain.AuthUser;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

class GoogleTokenVerifierTest {

	private MockWebServer server;
	private GoogleTokenVerifier verifier;

	@BeforeEach
	void setUp() throws IOException {
		server = new MockWebServer();
		server.start();
		AuthProperties properties = new AuthProperties();
		properties.setGoogleClientId("our-client-id.apps.googleusercontent.com");
		properties.setTokeninfoBaseUrl(server.url("/").toString().replaceAll("/$", ""));
		verifier = new GoogleTokenVerifier(properties, RestClient.builder());
	}

	@AfterEach
	void tearDown() throws IOException {
		server.shutdown();
	}

	@Test
	void returnsUserForTokenWithMatchingAudience() {
		server.enqueue(new MockResponse()
				.setBody("""
						{"aud":"our-client-id.apps.googleusercontent.com",
						 "sub":"google-sub-1","email":"user@example.com","email_verified":"true"}
						""")
				.addHeader("Content-Type", "application/json"));

		AuthUser user = verifier.verify("google-id-token");

		assertThat(user).isEqualTo(new AuthUser("google-sub-1", "user@example.com"));
	}

	@Test
	void rejectsTokenForDifferentAudience() {
		server.enqueue(new MockResponse()
				.setBody("{\"aud\":\"someone-else\",\"sub\":\"x\",\"email\":\"x@y.z\"}")
				.addHeader("Content-Type", "application/json"));

		assertThatThrownBy(() -> verifier.verify("google-id-token"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsInvalidToken() {
		server.enqueue(new MockResponse().setResponseCode(400)
				.setBody("{\"error\":\"invalid_token\"}")
				.addHeader("Content-Type", "application/json"));

		assertThatThrownBy(() -> verifier.verify("bad-token"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}

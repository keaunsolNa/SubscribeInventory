package com.dashboard.subscription.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import lombok.Getter;
import lombok.Setter;

/**
 * Binds {@code dashboard.auth.*}. Google-login auth activates only when both the OAuth client id
 * and the JWT signing secret are present; otherwise the legacy shared-token mode stays in effect
 * so local runs and gradual rollout keep working.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "dashboard.auth")
public class AuthProperties {

	private String googleClientId;
	/** Base64-encoded HMAC secret for session JWTs. */
	private String jwtSecret;
	private String tokeninfoBaseUrl = "https://oauth2.googleapis.com";

	public boolean isActive() {
		return StringUtils.hasText(googleClientId) && StringUtils.hasText(jwtSecret);
	}
}

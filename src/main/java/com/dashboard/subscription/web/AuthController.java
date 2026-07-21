package com.dashboard.subscription.web;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dashboard.subscription.config.AuthProperties;
import com.dashboard.subscription.domain.AuthUser;
import com.dashboard.subscription.service.GoogleTokenVerifier;
import com.dashboard.subscription.service.JwtService;

import lombok.extern.slf4j.Slf4j;

/**
 * Google-login endpoints: config discovery for the dashboard, ID-token exchange for a session
 * JWT, and the current-user probe.
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthProperties authProperties;
	private final GoogleTokenVerifier googleTokenVerifier;
	private final JwtService jwtService;
	private final String accessToken;

	public AuthController(AuthProperties authProperties, GoogleTokenVerifier googleTokenVerifier,
			JwtService jwtService, @Value("${dashboard.security.access-token:}") String accessToken) {
		this.authProperties = authProperties;
		this.googleTokenVerifier = googleTokenVerifier;
		this.jwtService = jwtService;
		this.accessToken = accessToken;
	}

	public record LoginRequest(String idToken) {
	}

	@GetMapping("/config")
	public Map<String, String> config() {
		Map<String, String> config = new HashMap<>();
		if (authProperties.isActive()) {
			config.put("mode", "google");
			config.put("googleClientId", authProperties.getGoogleClientId());
		} else {
			config.put("mode", StringUtils.hasText(accessToken) ? "token" : "open");
		}
		return config;
	}

	@PostMapping("/login")
	public Map<String, String> login(@RequestBody LoginRequest request) {
		if (!authProperties.isActive()) {
			throw new IllegalStateException("Google login is not configured on this deployment");
		}
		AuthUser user = googleTokenVerifier.verify(request.idToken());
		// Operational metric: unique emails here = signed-in account count (log-based, 30d window).
		log.info("Google login: email={}", user.email());
		return Map.of("token", jwtService.issue(user), "email", user.email());
	}

	@GetMapping("/me")
	public Map<String, String> me(@RequestAttribute(name = "authUser", required = false) AuthUser user) {
		if (user == null) {
			throw new IllegalStateException("not logged in");
		}
		return Map.of("email", user.email());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
		return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
	}

	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<Map<String, String>> unavailable(IllegalStateException exception) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(Map.of("error", exception.getMessage()));
	}
}

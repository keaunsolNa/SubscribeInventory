package com.dashboard.subscription.web;

import java.io.IOException;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.dashboard.subscription.config.AuthProperties;
import com.dashboard.subscription.domain.AuthUser;
import com.dashboard.subscription.service.JwtService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * API gate with three modes:
 * <ul>
 * <li>Google-login mode (auth configured): /api/** requires a session JWT; the authenticated
 * user lands in the {@link #USER_ATTRIBUTE} request attribute.</li>
 * <li>Legacy mode (only ACCESS_TOKEN set): the shared X-Access-Token header is required.</li>
 * <li>Open mode (nothing set): everything passes — local development.</li>
 * </ul>
 * Machine calls are honored in every mode: the scheduler's sweep (/api/alerts/check +
 * X-Access-Token) and the budget Pub/Sub push (/api/budget/notify?token=..., query param because
 * push requests cannot set headers). /api/health, /api/auth/login, /api/auth/config, and CORS
 * preflights stay open.
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

	static final String TOKEN_HEADER = "X-Access-Token";
	static final String USER_ATTRIBUTE = "authUser";
	private static final String BEARER_PREFIX = "Bearer ";

	private final AuthProperties authProperties;
	private final JwtService jwtService;
	private final String accessToken;

	public AuthFilter(AuthProperties authProperties, JwtService jwtService,
			@Value("${dashboard.security.access-token:}") String accessToken) {
		this.authProperties = authProperties;
		this.jwtService = jwtService;
		this.accessToken = accessToken;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String uri = request.getRequestURI();
		if (!uri.startsWith("/api/")) {
			return true;
		}
		if ("/api/health".equals(uri) || "/api/auth/login".equals(uri)
				|| "/api/auth/config".equals(uri)) {
			return true;
		}
		return HttpMethod.OPTIONS.matches(request.getMethod());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		if (isMachineCall(request)) {
			filterChain.doFilter(request, response);
			return;
		}
		if (authProperties.isActive()) {
			Optional<AuthUser> user = bearerUser(request);
			if (user.isPresent()) {
				request.setAttribute(USER_ATTRIBUTE, user.get());
				filterChain.doFilter(request, response);
				return;
			}
			reject(response, "login required");
			return;
		}
		if (!StringUtils.hasText(accessToken)
				|| accessToken.equals(request.getHeader(TOKEN_HEADER))) {
			filterChain.doFilter(request, response);
			return;
		}
		reject(response, "invalid or missing access token");
	}

	private boolean isMachineCall(HttpServletRequest request) {
		if (!StringUtils.hasText(accessToken)) {
			return false;
		}
		String uri = request.getRequestURI();
		if ("/api/alerts/check".equals(uri)) {
			return accessToken.equals(request.getHeader(TOKEN_HEADER));
		}
		if ("/api/budget/notify".equals(uri)) {
			return accessToken.equals(request.getParameter("token"));
		}
		return false;
	}

	private Optional<AuthUser> bearerUser(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.startsWith(BEARER_PREFIX)) {
			return Optional.empty();
		}
		return jwtService.verify(header.substring(BEARER_PREFIX.length()));
	}

	private void reject(HttpServletResponse response, String message) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"error\":\"" + message + "\"}");
	}
}

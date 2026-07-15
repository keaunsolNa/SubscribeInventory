package com.dashboard.subscription.web;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Optional shared-token gate for the API. Active only when {@code dashboard.security.access-token}
 * is set (e.g. on a public deployment); local setups without the property stay open.
 * {@code /api/health} is always open so platform health checks keep working.
 */
@Component
public class AccessTokenFilter extends OncePerRequestFilter {

	static final String TOKEN_HEADER = "X-Access-Token";

	private final String accessToken;

	public AccessTokenFilter(@Value("${dashboard.security.access-token:}") String accessToken) {
		this.accessToken = accessToken;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (!StringUtils.hasText(accessToken)) {
			return true;
		}
		String uri = request.getRequestURI();
		if (!uri.startsWith("/api/") || "/api/health".equals(uri)) {
			return true;
		}
		return HttpMethod.OPTIONS.matches(request.getMethod());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		if (accessToken.equals(request.getHeader(TOKEN_HEADER))) {
			filterChain.doFilter(request, response);
			return;
		}
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"error\":\"invalid or missing access token\"}");
	}
}

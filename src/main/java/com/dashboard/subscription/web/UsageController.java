package com.dashboard.subscription.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dashboard.subscription.service.CachedUsageService;

/**
 * Read-only dashboard endpoints.
 */
@RestController
@RequestMapping("/api")
public class UsageController {

	private final CachedUsageService cachedUsageService;

	public UsageController(CachedUsageService cachedUsageService) {
		this.cachedUsageService = cachedUsageService;
	}

	@GetMapping("/usage")
	public DashboardResponse usage() {
		return cachedUsageService.collect(Map.of());
	}

	/**
	 * BYOK variant: the client sends its own provider keys in the body; they are relayed upstream
	 * for this request only and never stored or logged.
	 */
	@PostMapping("/usage")
	public DashboardResponse usageWithCredentials(@RequestBody(required = false) UsageRequest request) {
		if (request == null || request.keys() == null) {
			return cachedUsageService.collect(Map.of());
		}
		return cachedUsageService.collect(request.keys());
	}

	@GetMapping("/health")
	public Map<String, String> health() {
		return Map.of("status", "UP");
	}
}

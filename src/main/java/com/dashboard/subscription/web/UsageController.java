package com.dashboard.subscription.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dashboard.subscription.domain.ApiCredentials;
import com.dashboard.subscription.service.CachedUsageService;
import com.dashboard.subscription.service.CredentialFingerprint;
import com.dashboard.subscription.service.UsageHistoryStore;
import com.dashboard.subscription.service.UsageHistoryStore.HistoryPoint;

/**
 * Read-only dashboard endpoints.
 */
@RestController
@RequestMapping("/api")
public class UsageController {

	private static final int HISTORY_DAYS = 7;

	private final CachedUsageService cachedUsageService;
	private final UsageHistoryStore usageHistoryStore;

	public UsageController(CachedUsageService cachedUsageService,
			UsageHistoryStore usageHistoryStore) {
		this.cachedUsageService = cachedUsageService;
		this.usageHistoryStore = usageHistoryStore;
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

	/**
	 * Hourly usage history for the credential set in the body (empty body = server-side keys).
	 * Keys are only fingerprinted to locate the history documents; empty when history is inactive.
	 */
	@PostMapping("/usage/history")
	public List<HistoryPoint> usageHistory(@RequestBody(required = false) UsageRequest request) {
		Map<String, ApiCredentials> keys =
				request == null || request.keys() == null ? Map.of() : request.keys();
		return usageHistoryStore.recent(CredentialFingerprint.of(keys), HISTORY_DAYS);
	}

	@GetMapping("/health")
	public Map<String, String> health() {
		return Map.of("status", "UP");
	}
}

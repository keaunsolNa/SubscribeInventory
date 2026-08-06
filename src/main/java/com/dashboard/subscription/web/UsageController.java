package com.dashboard.subscription.web;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dashboard.subscription.domain.ApiCredentials;
import com.dashboard.subscription.domain.AuthUser;
import com.dashboard.subscription.service.CachedUsageService;
import com.dashboard.subscription.service.CredentialFingerprint;
import com.dashboard.subscription.service.UsageHistoryStore;
import com.dashboard.subscription.service.UsageHistoryStore.HistoryPoint;
import com.dashboard.subscription.service.UsageHistoryStore.ProviderSpend;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-only dashboard endpoints.
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class UsageController {

	private static final int HISTORY_DAYS = 7;

	private final CachedUsageService cachedUsageService;
	private final UsageHistoryStore usageHistoryStore;
	private final Clock clock;

	public UsageController(CachedUsageService cachedUsageService,
			UsageHistoryStore usageHistoryStore, Clock clock) {
		this.cachedUsageService = cachedUsageService;
		this.usageHistoryStore = usageHistoryStore;
		this.clock = clock;
	}

	@GetMapping("/usage")
	public DashboardResponse usage(HttpServletRequest request) {
		logTraffic(request, false);
		return cachedUsageService.collect(Map.of());
	}

	/**
	 * BYOK variant: the client sends its own provider keys in the body; they are relayed upstream
	 * for this request only and never stored or logged.
	 */
	@PostMapping("/usage")
	public DashboardResponse usageWithCredentials(@RequestBody(required = false) UsageRequest request,
			HttpServletRequest httpRequest) {
		boolean byok = request != null && request.keys() != null;
		logTraffic(httpRequest, byok);
		return cachedUsageService.collect(byok ? request.keys() : Map.of());
	}

	/**
	 * Operational traffic metric. The signed-in account is the reliable way to exclude the
	 * operator's own traffic — Chrome's IP Protection can mask the client IP behind rotating
	 * Google proxy addresses, so the IP alone cannot. Only IP and account are logged — never keys.
	 */
	private void logTraffic(HttpServletRequest request, boolean byok) {
		Object user = request.getAttribute(AuthFilter.USER_ATTRIBUTE);
		String account = user instanceof AuthUser authUser ? authUser.email() : "-";
		log.info("Usage query: ip={} byok={} user={}", request.getRemoteAddr(), byok, account);
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

	/**
	 * Balance consumed this UTC month, per provider. Only prepaid-balance providers appear here:
	 * the ones that report a cumulative month-to-date cost already answer this exactly, and the
	 * client has that live figure from {@code /api/usage}. Each entry carries its observed window
	 * because history is only written while the dashboard is open, so a sparse month under-counts
	 * and the client has to be able to show that rather than pass a gap off as zero spend.
	 */
	@PostMapping("/usage/monthly")
	public MonthlySpendResponse monthlySpend(@RequestBody(required = false) UsageRequest request) {
		Map<String, ApiCredentials> keys =
				request == null || request.keys() == null ? Map.of() : request.keys();
		YearMonth month = YearMonth.now(clock.withZone(ZoneOffset.UTC));
		return new MonthlySpendResponse(month.toString(),
				usageHistoryStore.monthlyConsumption(CredentialFingerprint.of(keys), month));
	}

	public record MonthlySpendResponse(String month, List<ProviderSpend> providers) {
	}

	@GetMapping("/health")
	public Map<String, String> health() {
		return Map.of("status", "UP");
	}
}

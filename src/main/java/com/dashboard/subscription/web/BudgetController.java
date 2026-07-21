package com.dashboard.subscription.web;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dashboard.subscription.service.BudgetAlertService;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pub/Sub push target for Cloud Billing budget notifications. Push requests cannot carry custom
 * headers, so {@link AuthFilter} admits this path via the {@code ?token=} query parameter instead.
 * Always answers 200 — Pub/Sub retries anything else, and budget updates are re-published anyway.
 */
@RestController
@RequestMapping("/api/budget")
public class BudgetController {

	private final BudgetAlertService budgetAlertService;

	public BudgetController(BudgetAlertService budgetAlertService) {
		this.budgetAlertService = budgetAlertService;
	}

	@PostMapping("/notify")
	public Map<String, String> notify(@RequestBody JsonNode envelope) {
		budgetAlertService.handle(envelope);
		return Map.of("status", "ok");
	}

	/** Cloud Scheduler target: weekly spend digest to Slack (Monday mornings, KST). */
	@PostMapping("/report")
	public Map<String, String> report() {
		budgetAlertService.sendWeeklyReport();
		return Map.of("status", "ok");
	}
}

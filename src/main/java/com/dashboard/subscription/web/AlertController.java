package com.dashboard.subscription.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dashboard.subscription.domain.AlertSubscription;
import com.dashboard.subscription.service.AlertSubscriptionService;
import com.dashboard.subscription.service.SweepResult;

/**
 * Alert subscription management plus the scheduled sweep trigger. All paths live under /api so
 * the access-token filter protects them on public deployments.
 */
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

	private final AlertSubscriptionService alertSubscriptionService;

	public AlertController(AlertSubscriptionService alertSubscriptionService) {
		this.alertSubscriptionService = alertSubscriptionService;
	}

	@PostMapping("/subscriptions")
	public Map<String, String> subscribe(@RequestBody AlertSubscription subscription) {
		return Map.of("id", alertSubscriptionService.subscribe(subscription));
	}

	@DeleteMapping("/subscriptions/{subscriptionId}")
	public ResponseEntity<Void> unsubscribe(@PathVariable String subscriptionId) {
		alertSubscriptionService.unsubscribe(subscriptionId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/check")
	public SweepResult check() {
		return alertSubscriptionService.sweep();
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

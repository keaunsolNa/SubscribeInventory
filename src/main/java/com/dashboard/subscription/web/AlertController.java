package com.dashboard.subscription.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dashboard.subscription.service.AlertCheckResult;
import com.dashboard.subscription.service.AlertService;

/**
 * Alert sweep trigger, called periodically by Cloud Scheduler. Lives under /api so the
 * access-token filter protects it on public deployments.
 */
@RestController
@RequestMapping("/api")
public class AlertController {

	private final AlertService alertService;

	public AlertController(AlertService alertService) {
		this.alertService = alertService;
	}

	@PostMapping("/alerts/check")
	public AlertCheckResult check() {
		return alertService.check();
	}
}

package com.dashboard.subscription.web;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dashboard.subscription.domain.AuthUser;
import com.dashboard.subscription.service.FeedbackService;

/**
 * Feedback intake. Auth is enforced by {@link AuthFilter} like every /api/** path, so in Google
 * mode only signed-in users can submit — which both attributes the feedback and bounds abuse.
 */
@RestController
@RequestMapping("/api")
public class FeedbackController {

	private final FeedbackService feedbackService;

	public FeedbackController(FeedbackService feedbackService) {
		this.feedbackService = feedbackService;
	}

	public record FeedbackRequest(String message) {
	}

	@PostMapping("/feedback")
	public Map<String, String> submit(@RequestBody FeedbackRequest request,
			@RequestAttribute(name = AuthFilter.USER_ATTRIBUTE, required = false) AuthUser user) {
		feedbackService.submit(request == null ? null : request.message(), user);
		return Map.of("status", "ok");
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
		return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
	}
}

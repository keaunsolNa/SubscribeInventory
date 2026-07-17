package com.dashboard.subscription.service;

/**
 * Outcome of one scheduled alert sweep across all subscriptions.
 */
public record SweepResult(boolean enabled, int subscriptions, int notified) {
}

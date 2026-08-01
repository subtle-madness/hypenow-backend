package com.celfit.was.v1.admin;

/**
 * GET /v1/admin/users/{id} events[] 1건(설계 2026-08-01 §4·§0 A5) — type은
 * signup/campaign_created/monitoring_registered/content_saved/influencer_saved 5종, at은 KST ISO.
 */
public record AdminUserEvent(String type, String label, String at) {
}

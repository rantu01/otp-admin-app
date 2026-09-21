package com.otpfetch.admin;

/**
 * Central backend/API URL configuration.
 *
 * Single place to change the backend URL in the future — every API call
 * goes through AdminSession.getBase(), which defaults to this value.
 * Admins never enter the URL manually anywhere in the app.
 */
public final class ApiConfig {
    private ApiConfig() {}

    /** Default backend/API base URL (no trailing slash). */
    public static final String DEFAULT_BASE_URL = "https://otp.rantumondal.dev";
}

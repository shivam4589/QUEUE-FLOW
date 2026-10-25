package com.example.jobqueue.apikey;

/** Response returned (once) when a new API key is created. */
public record CreateApiKeyResponse(ApiKey key, String rawKey) {
}

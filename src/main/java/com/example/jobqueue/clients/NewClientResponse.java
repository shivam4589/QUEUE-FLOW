package com.example.jobqueue.clients;

/** Returned once, when a client is created: apiKey is not retrievable afterwards. */
public record NewClientResponse(ApiClient client, String apiKey) {
}

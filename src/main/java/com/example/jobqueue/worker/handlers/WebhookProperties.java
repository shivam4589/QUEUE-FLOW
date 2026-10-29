package com.example.jobqueue.worker.handlers;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * jobqueue.webhook.allow-private-networks  allow targets on private, loopback and link-local addresses (off by default:
 *                                          it stops a job from being used to probe the server's own network)
 * jobqueue.webhook.allow-http              allow plain http:// (off by default; https only)
 * jobqueue.webhook.timeout-seconds         connect and response timeout
 * jobqueue.webhook.max-response-bytes      how much of the response is read
 */
@ConfigurationProperties(prefix = "jobqueue.webhook")
public record WebhookProperties(
        @DefaultValue("false") boolean allowPrivateNetworks,
        @DefaultValue("false") boolean allowHttp,
        @DefaultValue("10") int timeoutSeconds,
        @DefaultValue("65536") int maxResponseBytes) {
}

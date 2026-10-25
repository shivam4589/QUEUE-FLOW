package com.example.jobqueue.apikey;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateApiKeyRequest(
        @NotBlank @Size(max = 100) String clientId,
        @Size(max = 500) String description) {
}

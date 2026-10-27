package com.example.jobqueue.clients;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateClientRequest(@NotBlank @Size(max = 100) String name) {
}

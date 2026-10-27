package com.example.jobqueue.limits;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SetLimitRequest(
        @NotBlank @Size(max = 100) String type,
        @NotNull @Min(1) @Max(1000) Integer maxConcurrent) {
}

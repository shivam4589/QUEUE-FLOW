package com.example.jobqueue.clients;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Both fields are optional: leave one out (null) to leave it unlimited. */
public record SetClientLimitsRequest(
        @Min(1) @Max(100000) Integer ratePerMinute,
        @Min(1) @Max(10000000) Integer dailyQuota) {
}

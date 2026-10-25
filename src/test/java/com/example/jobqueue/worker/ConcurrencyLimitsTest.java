package com.example.jobqueue.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ConcurrencyLimitsTest {

    @Test
    void emptyLimits() {
        ConcurrencyLimits limits = new ConcurrencyLimits(null);
        assertThat(limits.isEmpty()).isTrue();
        assertThat(limits.limitFor("SEND_EMAIL")).isEqualTo(-1);
        assertThat(limits.toJsonb()).isEqualTo("{}");

        ConcurrencyLimits emptyMap = new ConcurrencyLimits(Map.of());
        assertThat(emptyMap.isEmpty()).isTrue();
        assertThat(emptyMap.toJsonb()).isEqualTo("{}");
    }

    @Test
    void limitsWithValues() {
        ConcurrencyLimits limits = new ConcurrencyLimits(Map.of("SEND_EMAIL", 3, "SLOW", 1));
        assertThat(limits.isEmpty()).isFalse();
        assertThat(limits.limitFor("SEND_EMAIL")).isEqualTo(3);
        assertThat(limits.limitFor("SLOW")).isEqualTo(1);
        assertThat(limits.limitFor("UNKNOWN")).isEqualTo(-1);

        String json = limits.toJsonb();
        assertThat(json).contains("\"SEND_EMAIL\":3");
        assertThat(json).contains("\"SLOW\":1");
    }
}

package com.taskorchestrator.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryBackoffTest {

    @Test
    void defaultsDoubleAndCapAtEightSeconds() {
        RetryBackoff backoff = RetryBackoff.defaults();
        assertThat(backoff.delayAfterAttempt(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.delayAfterAttempt(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(backoff.delayAfterAttempt(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(backoff.delayAfterAttempt(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(backoff.delayAfterAttempt(5)).isEqualTo(Duration.ofSeconds(8));
        assertThat(backoff.delayAfterAttempt(1_000)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void zeroOrNegativeAttemptTreatedAsFirst() {
        assertThat(RetryBackoff.defaults().delayAfterAttempt(0)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new RetryBackoff(Duration.ofSeconds(5), 2, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryBackoff(Duration.ofSeconds(1), 0.5, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

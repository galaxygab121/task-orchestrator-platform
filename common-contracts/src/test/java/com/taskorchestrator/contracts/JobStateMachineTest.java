package com.taskorchestrator.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JobStateMachineTest {

    @Test
    void allowsDocumentedHappyPathAndRetry() {
        assertThat(JobStateMachine.canTransition(JobStatus.SUBMITTED, JobStatus.QUEUED)).isTrue();
        assertThat(JobStateMachine.canTransition(JobStatus.QUEUED, JobStatus.RUNNING)).isTrue();
        assertThat(JobStateMachine.canTransition(JobStatus.RUNNING, JobStatus.SUCCEEDED)).isTrue();
        assertThat(JobStateMachine.canTransition(JobStatus.RUNNING, JobStatus.QUEUED)).isTrue();
        assertThat(JobStateMachine.canTransition(JobStatus.RUNNING, JobStatus.FAILED)).isTrue();
    }

    @Test
    void cancellationOnlyBeforeRunning() {
        assertThat(JobStateMachine.canTransition(JobStatus.SUBMITTED, JobStatus.CANCELLED)).isTrue();
        assertThat(JobStateMachine.canTransition(JobStatus.QUEUED, JobStatus.CANCELLED)).isTrue();
        assertThat(JobStateMachine.canTransition(JobStatus.RUNNING, JobStatus.CANCELLED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"SUCCEEDED", "FAILED", "CANCELLED"})
    void terminalStatesHaveNoExits(JobStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (JobStatus to : JobStatus.values()) {
            assertThat(JobStateMachine.canTransition(terminal, to)).as(terminal + "->" + to).isFalse();
        }
    }

    @Test
    void exactlyTheDocumentedTransitionsExist() {
        int count = 0;
        for (JobStatus from : JobStatus.values()) {
            count += JobStateMachine.allowedFrom(from).size();
        }
        assertThat(count).isEqualTo(7);
        assertThat(JobStateMachine.allowedFrom(JobStatus.RUNNING))
                .isEqualTo(Set.copyOf(EnumSet.of(JobStatus.SUCCEEDED, JobStatus.QUEUED, JobStatus.FAILED)));
    }

    @Test
    void requireTransitionThrowsWithContext() {
        assertThatThrownBy(() -> JobStateMachine.requireTransition(JobStatus.SUCCEEDED, JobStatus.RUNNING))
                .isInstanceOf(InvalidJobTransitionException.class)
                .hasMessageContaining("SUCCEEDED -> RUNNING");
        assertThat(JobStateMachine.canTransition(null, JobStatus.QUEUED)).isFalse();
    }
}

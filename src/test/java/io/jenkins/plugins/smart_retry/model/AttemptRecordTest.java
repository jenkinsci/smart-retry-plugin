package io.jenkins.plugins.smart_retry.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class AttemptRecordTest {
    @Test
    void summaryDisplayFallsBackWhenMissing() {
        AttemptRecord attempt = new AttemptRecord(1, FailureType.UNKNOWN, null, false, 0, "FAILED", null);

        assertEquals("n/a", attempt.getSummaryDisplay());
    }
}

package io.jenkins.plugins.smart_retry.model;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;
import jenkins.management.Badge;

public final class AttemptRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String RETRY_SCHEDULED = "Retry scheduled";
    private static final String NO_RETRY = "No retry";

    private final int attemptNumber;
    private final FailureType failureType;
    private final String matchedRule;
    private final boolean retried;
    private final long delayMillis;
    private final String outcome;
    private final String summary;

    public AttemptRecord(
            int attemptNumber,
            FailureType failureType,
            @CheckForNull String matchedRule,
            boolean retried,
            long delayMillis,
            String outcome,
            String summary) {
        this.attemptNumber = attemptNumber;
        this.failureType = Objects.requireNonNull(failureType, "failureType must not be null");
        this.matchedRule = normalizeMatchedRule(matchedRule);
        this.retried = retried;
        this.delayMillis = delayMillis;
        this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        this.summary = Objects.requireNonNull(summary, "Summary must not be null");
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public FailureType getFailureType() {
        return failureType;
    }

    @CheckForNull
    public String getMatchedRule() {
        return matchedRule;
    }

    public boolean isRetried() {
        return retried;
    }

    public long getDelayMillis() {
        return delayMillis;
    }

    public String getDelayDisplay() {
        if (delayMillis == 0) {
            return "0 ms";
        }
        if (delayMillis < 1000) {
            return delayMillis + " ms";
        }
        long seconds = delayMillis / 1000;
        long remainder = delayMillis % 1000;
        if (remainder == 0) {
            return seconds + " s";
        }
        return seconds + "." + String.format("%03d", remainder) + " s";
    }

    public String getOutcome() {
        return outcome;
    }

    public String getMatchedRuleDisplay() {
        if (matchedRule == null) {
            return "n/a";
        }
        return matchedRule;
    }

    public String getFailureTypeDocumentationAnchor() {
        return "failure-type-" + slugify(failureType.name());
    }

    public String getFailureTypeDetailsDocumentationAnchor() {
        return "detail-" + getFailureTypeDocumentationAnchor();
    }

    @CheckForNull
    public String getMatchedRuleDocumentationAnchor() {
        if (matchedRule == null) {
            return null;
        }
        return "rule-" + slugify(matchedRule);
    }

    public String getRetryDecisionDisplay() {
        return retried ? RETRY_SCHEDULED : NO_RETRY;
    }

    public Badge getRetryDecisionBadge() {
        if (retried) {
            return new Badge(RETRY_SCHEDULED, "Smart Retry scheduled another attempt.", Badge.Severity.INFO);
        }
        return new Badge(NO_RETRY, "Smart Retry stopped after this attempt.", Badge.Severity.WARNING);
    }

    public String getOutcomeDisplay() {
        return formatDisplayValue(outcome);
    }

    public Badge getOutcomeBadge() {
        if ("FAILED".equals(outcome)) {
            return new Badge("Failed", "This attempt ended the Smart Retry flow.", Badge.Severity.DANGER);
        }
        if ("RETRY_SCHEDULED".equals(outcome)) {
            return new Badge(RETRY_SCHEDULED, "This attempt triggered another retry.", Badge.Severity.INFO);
        }
        return new Badge(getOutcomeDisplay(), "Smart Retry recorded this outcome.", Badge.Severity.INFO);
    }

    public String getSummaryDisplay() {
        return formatDisplayValue(summary);
    }

    @CheckForNull
    private static String normalizeMatchedRule(@CheckForNull String matchedRule) {
        if (matchedRule == null || matchedRule.isBlank()) {
            return null;
        }
        return matchedRule;
    }

    private static String formatDisplayValue(String value) {
        String normalized = value.replace('_', ' ').toLowerCase();
        String[] parts = normalized.split(" ");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.toString();
    }

    private static String slugify(String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}

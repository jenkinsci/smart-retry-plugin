package io.jenkins.plugins.smart_retry.step;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.smart_retry.action.SmartRetryRunAction;
import io.jenkins.plugins.smart_retry.classify.DeterministicFailureClassifier;
import io.jenkins.plugins.smart_retry.classify.FailureClassifier;
import io.jenkins.plugins.smart_retry.config.CustomClassificationRule;
import io.jenkins.plugins.smart_retry.config.SmartRetryGlobalConfiguration;
import io.jenkins.plugins.smart_retry.model.AttemptRecord;
import io.jenkins.plugins.smart_retry.model.FailureClassification;
import io.jenkins.plugins.smart_retry.model.RetryDecision;
import io.jenkins.plugins.smart_retry.policy.BuiltInProfiles;
import io.jenkins.plugins.smart_retry.policy.DeterministicRetryPolicy;
import io.jenkins.plugins.smart_retry.policy.RetryPolicy;
import io.jenkins.plugins.smart_retry.policy.RuntimeSettings;
import java.io.IOException;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.util.Timer;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

public class SmartRetryStepExecution extends StepExecution {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(SmartRetryStepExecution.class.getName());
    private static final String ATTEMPT_MARKER_PREFIX = "[smartRetry] begin attempt=";
    private static final int DEFAULT_LOG_CONTEXT_MAX_LINES = 200;
    private static final String LOG_CLASSIFIED = " classified=";
    private static final String LOG_REASON = " reason=\"";

    private final String profile;
    private final Integer maxRetries;
    private final String backoff;
    private final Integer initialDelaySeconds;

    private RuntimeSettings resolvedSettings;
    private int resolvedConsoleContextLines = DEFAULT_LOG_CONTEXT_MAX_LINES;
    private Set<String> resolvedDisabledBuiltInRuleIds = Set.of();
    private List<CustomClassificationRule> resolvedCustomClassificationRules = List.of();
    private int attemptNumber = 1;
    private long waitingUntilMillis;
    private transient ScheduledFuture<?> waitingTask;

    SmartRetryStepExecution(SmartRetryStep step, StepContext context) {
        super(context);
        this.profile = step.getProfile();
        this.maxRetries = step.getMaxRetries();
        this.backoff = step.getBackoff();
        this.initialDelaySeconds = step.getInitialDelaySeconds();
    }

    @Override
    public boolean start() throws Exception {
        resolveExecutionConfiguration();
        initializeRunAction();
        startAttempt();
        return false;
    }

    @Override
    public void onResume() {
        synchronized (this) {
            if (waitingUntilMillis <= 0L) {
                return;
            }
        }
        scheduleRetry(System.currentTimeMillis());
    }

    @Override
    public void stop(Throwable cause) throws Exception {
        cancelWaitingTask();
        super.stop(cause);
    }

    private void startAttempt() {
        try {
            TaskListener listener = getContext().get(TaskListener.class);
            if (listener != null) {
                listener.getLogger().println(ATTEMPT_MARKER_PREFIX + attemptNumber);
            }
        } catch (Exception e) {
            restoreInterruptIfNeeded(e);
            LOGGER.log(Level.FINE, "smartRetry attempt marker logging failed", e);
        }
        getContext()
                .newBodyInvoker()
                .withDisplayName(stepLabel())
                .withCallback(new SmartRetryBodyCallback())
                .start();
    }

    private String stepLabel() {
        if (resolvedSettings == null
                || resolvedSettings.getProfile() == null
                || resolvedSettings.getProfile().isBlank()) {
            return "smartRetry (attempt " + attemptNumber + ")";
        }
        return "smartRetry (" + resolvedSettings.getProfile() + ", attempt " + attemptNumber + ")";
    }

    private final class SmartRetryBodyCallback extends BodyExecutionCallback {

        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public void onSuccess(StepContext context, Object result) {
            logFinalSuccess(context);
            markSuccess(context);
            context.onSuccess(result);
        }

        @Override
        public void onFailure(StepContext context, Throwable t) {
            try {
                RuntimeSettings settings = resolvedSettings;
                FailureClassifier classifier = new DeterministicFailureClassifier(
                        resolvedDisabledBuiltInRuleIds, resolvedCustomClassificationRules);
                RetryPolicy policy = new DeterministicRetryPolicy();

                String messageContext = loadAttemptConsoleContext(context, attemptNumber, resolvedConsoleContextLines);
                FailureClassification classification = classifier.classify(t, messageContext);
                RetryDecision decision = policy.decide(classification, settings, attemptNumber);

                TaskListener listener = context.get(TaskListener.class);
                if (listener != null) {
                    listener.getLogger()
                            .println("[smartRetry] attempt=" + attemptNumber
                                    + " profile=" + settings.getProfile()
                                    + LOG_CLASSIFIED + classification.getType()
                                    + " retryCandidate=" + classification.isRetryCandidate()
                                    + " decision=" + (decision.shouldRetry() ? "RETRY" : "FAIL")
                                    + " nextAttempt=" + decision.getNextAttemptNumber()
                                    + " delayMillis=" + decision.getDelayMillis()
                                    + LOG_REASON + decision.getReason() + "\"");
                }

                recordAttempt(context, settings.getProfile(), classification, decision);

                if (decision.shouldRetry()) {
                    logRetryScheduling(context, settings.getProfile(), classification, decision);
                    attemptNumber = decision.getNextAttemptNumber();
                    // SpotBug Fix
                    synchronized (SmartRetryStepExecution.this) {
                        waitingUntilMillis = System.currentTimeMillis() + decision.getDelayMillis();
                    }
                    scheduleRetry(System.currentTimeMillis());
                    return;
                }

                // Final failure path.
                logFinalFailure(context, settings.getProfile(), classification, decision);
                setFinalOutcome(context, "FAILED");
            } catch (Exception e) {
                restoreInterruptIfNeeded(e);
                LOGGER.log(Level.FINE, "smartRetry decision logging failed", e);
            }
            context.onFailure(t);
        }
    }

    private void recordAttempt(
            StepContext context, String profile, FailureClassification classification, RetryDecision decision) {
        SmartRetryRunAction action = getOrCreateRunAction(context);
        if (action == null) {
            return;
        }
        action.setProfile(profile);

        boolean retried = decision.shouldRetry();
        String outcome = retried ? "RETRY_SCHEDULED" : "FAILED";
        action.addAttempt(new AttemptRecord(
                attemptNumber,
                classification.getType(),
                classification.getMatchedRule(),
                retried,
                decision.getDelayMillis(),
                outcome));
    }

    private void markSuccess(StepContext context) {
        SmartRetryRunAction action = getOrCreateRunAction(context);
        if (action == null) {
            return;
        }
        action.setProfile(resolvedSettings == null ? null : resolvedSettings.getProfile());
        action.setFinalOutcome("SUCCESS");
    }

    private void setFinalOutcome(StepContext context, String outcome) {
        SmartRetryRunAction action = getOrCreateRunAction(context);
        if (action == null) {
            return;
        }
        action.setProfile(resolvedSettings == null ? null : resolvedSettings.getProfile());
        action.setFinalOutcome(outcome);
    }

    private void resolveExecutionConfiguration() {
        SmartRetryGlobalConfiguration cfg = SmartRetryGlobalConfiguration.get();
        if (cfg == null) {
            String effectiveProfile = profile;
            if (effectiveProfile == null || effectiveProfile.isBlank()) {
                effectiveProfile = BuiltInProfiles.PROFILE_CONSERVATIVE;
            }
            RuntimeSettings defaults = BuiltInProfiles.defaultsFor(effectiveProfile);
            resolvedSettings = BuiltInProfiles.resolve(defaults, maxRetries, backoff, initialDelaySeconds);
            resolvedConsoleContextLines = DEFAULT_LOG_CONTEXT_MAX_LINES;
            resolvedDisabledBuiltInRuleIds = Set.of();
            resolvedCustomClassificationRules = List.of();
            return;
        }
        resolvedSettings = cfg.resolveStepSettings(profile, maxRetries, backoff, initialDelaySeconds);
        resolvedConsoleContextLines = Math.max(0, cfg.getConsoleContextLines());
        resolvedDisabledBuiltInRuleIds = cfg.getDisabledBuiltInRuleIds();
        resolvedCustomClassificationRules = new ArrayList<>(cfg.getCustomClassificationRules());
    }

    @CheckForNull
    private static String loadAttemptConsoleContext(StepContext context, int attemptNumber, int maxLines) {
        if (maxLines <= 0) {
            return null;
        }
        Run<?, ?> run;
        try {
            run = context.get(Run.class);
        } catch (Exception e) {
            restoreInterruptIfNeeded(e);
            return null;
        }
        if (run == null) {
            return null;
        }
        List<String> tail;
        try {
            tail = run.getLog(maxLines);
        } catch (IOException e) {
            return null;
        }
        if (tail.isEmpty()) {
            return null;
        }

        String marker = ATTEMPT_MARKER_PREFIX + attemptNumber;
        int startIndex = 0;
        for (int i = tail.size() - 1; i >= 0; i--) {
            if (tail.get(i).contains(marker)) {
                startIndex = i + 1;
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = startIndex; i < tail.size(); i++) {
            String line = tail.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(line);
        }
        if (sb.isEmpty()) {
            return null;
        }
        return sb.toString();
    }

    private void scheduleRetry(long nowMillis) {
        long remainingMillis;
        synchronized (this) {
            remainingMillis = Math.max(0L, waitingUntilMillis - nowMillis);
            if (remainingMillis == 0L) {
                waitingUntilMillis = 0L;
                waitingTask = null;
            } else {
                ScheduledFuture<?> existing = waitingTask;
                if (existing != null) {
                    existing.cancel(false);
                }
                waitingTask =
                        Timer.get().schedule(this::resumeScheduledAttempt, remainingMillis, TimeUnit.MILLISECONDS);
                return;
            }
        }
        startAttempt();
    }

    private void initializeRunAction() {
        SmartRetryRunAction action = getOrCreateRunAction(getContext());
        if (action == null) {
            return;
        }

        if (resolvedSettings != null) {
            action.setProfile(resolvedSettings.getProfile());
            action.setEffectiveMaxRetries(resolvedSettings.getMaxRetries());

            if (resolvedSettings.getBackoff() != null) {
                action.setEffectiveBackoff(
                        resolvedSettings.getBackoff().getClass().getSimpleName());
            } else {
                action.setEffectiveBackoff(null);
            }

            action.setEffectiveInitialDelaySeconds(resolvedSettings.getInitialDelaySeconds());
        } else {
            action.setProfile(null);
        }
    }

    @CheckForNull
    private static SmartRetryRunAction getOrCreateRunAction(StepContext context) {
        Run<?, ?> run;
        try {
            run = context.get(Run.class);
        } catch (Exception e) {
            restoreInterruptIfNeeded(e);
            return null;
        }
        if (run == null) {
            return null;
        }
        return SmartRetryRunAction.getOrCreate(run);
    }

    private void logRetryScheduling(
            StepContext context, String profile, FailureClassification classification, RetryDecision decision) {
        logLine(
                context,
                "[smartRetry] scheduling profile="
                        + profile
                        + LOG_CLASSIFIED
                        + classification.getType()
                        + " nextAttempt="
                        + decision.getNextAttemptNumber()
                        + " delayMillis="
                        + decision.getDelayMillis());
    }

    private void logFinalSuccess(StepContext context) {
        int retriesUsed = Math.max(0, attemptNumber - 1);
        String reason = retriesUsed == 0
                ? "Body succeeded on first attempt"
                : "Recovered after " + retriesUsed + " scheduled " + (retriesUsed == 1 ? "retry" : "retries");
        logLine(
                context,
                "[smartRetry] completed profile="
                        + effectiveProfileName()
                        + " result=SUCCESS attempts="
                        + attemptNumber
                        + " retriesUsed="
                        + retriesUsed
                        + LOG_REASON
                        + reason
                        + "\"");
    }

    private void logFinalFailure(
            StepContext context, String profile, FailureClassification classification, RetryDecision decision) {
        logLine(
                context,
                "[smartRetry] completed profile="
                        + profile
                        + " result=FAILED attempts="
                        + attemptNumber
                        + LOG_CLASSIFIED
                        + classification.getType()
                        + LOG_REASON
                        + decision.getReason()
                        + "\"");
    }

    private void logLine(StepContext context, String line) {
        try {
            TaskListener listener = context.get(TaskListener.class);
            if (listener != null) {
                listener.getLogger().println(line);
            }
        } catch (Exception e) {
            restoreInterruptIfNeeded(e);
            LOGGER.log(Level.FINE, "smartRetry summary logging failed", e);
        }
    }

    private String effectiveProfileName() {
        if (resolvedSettings == null
                || resolvedSettings.getProfile() == null
                || resolvedSettings.getProfile().isBlank()) {
            return BuiltInProfiles.PROFILE_CONSERVATIVE;
        }
        return resolvedSettings.getProfile();
    }

    private synchronized void cancelWaitingTask() {
        ScheduledFuture<?> task = waitingTask;
        waitingTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    private void resumeScheduledAttempt() {
        synchronized (this) {
            waitingUntilMillis = 0L;
            waitingTask = null;
        }
        startAttempt();
    }

    static void restoreInterruptIfNeeded(Exception exception) {
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}

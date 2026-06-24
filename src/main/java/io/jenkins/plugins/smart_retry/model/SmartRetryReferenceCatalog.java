package io.jenkins.plugins.smart_retry.model;

import io.jenkins.plugins.smart_retry.classify.DeterministicFailureClassifier;
import io.jenkins.plugins.smart_retry.config.CustomClassificationRule;
import io.jenkins.plugins.smart_retry.config.CustomProfileSettings;
import io.jenkins.plugins.smart_retry.config.SmartRetryGlobalConfiguration;
import io.jenkins.plugins.smart_retry.policy.BuiltInProfiles;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jenkins.management.Badge;

public final class SmartRetryReferenceCatalog {

    private static final String TRIGGER_KIND_MESSAGE_PATTERN = "Message pattern";
    private static final String TRIGGER_KIND_EXCEPTION_TYPE = "Exception type";
    private static final String DEFAULT_BEHAVIOR_RETRY_CANDIDATE = "Retry candidate";
    private static final String DEFAULT_BEHAVIOR_NEVER_RETRY = "Never retry";
    private static final String PROFILE_BEHAVIOR_RETRY_ALLOWED = "Retry allowed";
    private static final String PROFILE_BEHAVIOR_NO_RETRY = "No retry";
    private static final String CUSTOM_BEHAVIOR_CONFIGURABLE = "Configurable";
    private static final String DISABLEABLE_YES = "Yes";

    private static final Set<String> SUPPORTED_DISABLED_BUILT_IN_RULE_IDS =
            DeterministicFailureClassifier.supportedDisabledBuiltInRuleIds();

    public static Set<String> getSupportedDisabledBuiltInRuleIds() {
        return Collections.unmodifiableSet(SUPPORTED_DISABLED_BUILT_IN_RULE_IDS);
    }

    private static final List<FailureTypeDoc> FAILURE_TYPES = List.of(
            failureType(
                    FailureType.AGENT_LOST,
                    "Agent, pod, node, or remoting channel disappeared during execution.",
                    "Current classifier emits this type for agent removal, remoting channel closure, offline/launcher initialization breakage, Kubernetes pod deleted/not-found signals, eviction/resource-pressure failures, and other channel-loss style signals.",
                    "This usually points to execution infrastructure disappearing underneath the build."),
            failureType(
                    FailureType.SCM_TRANSIENT,
                    "Checkout or fetch failed because of temporary SCM or network instability.",
                    "Current classifier emits this type for transient Git remote disconnects, Git transport interruptions, and SCM host-resolution failures.",
                    "These failures are usually external to the repository contents and are safe retry candidates."),
            failureType(
                    FailureType.NETWORK_TRANSIENT,
                    "An external service could not be reached reliably.",
                    "Current classifier emits this type for generic host resolution, explicit HTTP-style 5xx, TLS handshake timeout, refused connections to external services, connect, timeout, reset, and EOF style failures.",
                    "These failures often clear on a later attempt, but the conservative profile intentionally blocks them."),
            failureType(
                    FailureType.ARTIFACT_REPO_TRANSIENT,
                    "A dependency or artifact repository was temporarily unavailable.",
                    "Current classifier emits this type only when repository-specific context is present around transient 5xx, TLS handshake timeout, connection reset/refused, or partial Maven artifact download failures.",
                    "Repository-side outages are often short-lived, but only broader profiles will retry them."),
            failureType(
                    FailureType.IDENTITY_PROVIDER_TRANSIENT,
                    "An external identity provider failed during an authentication or reauthentication flow.",
                    "Current classifier emits this type only for narrow LDAP reauthentication failure signals rather than generic 401 responses.",
                    "Identity backend instability can clear on a later attempt, but Smart Retry keeps this out of the conservative profile."),
            failureType(
                    FailureType.SCM_CONFIGURATION_FAILURE,
                    "The requested SCM revision, branch, tag, commit, or checkout target does not exist.",
                    "Current classifier emits this type for deterministic revision-not-found and branch-not-found failures rather than transient transport problems.",
                    "This is a configuration or input error, so Smart Retry never retries it."),
            failureType(
                    FailureType.USER_ABORT,
                    "The build was intentionally interrupted by a user or explicit interruption path.",
                    "Current classifier emits this type for FlowInterruptedException paths.",
                    "Intentional interrupts should stop immediately and must not be retried."),
            failureType(
                    FailureType.PIPELINE_LOGIC_FAILURE,
                    "The Jenkinsfile or Pipeline script logic is broken.",
                    "Current classifier emits this type for missing DSL methods, missing properties, script-security rejections, and WorkflowScript/Jenkinsfile Groovy startup failures.",
                    "Retrying broken pipeline logic would repeat the same user-authored error."),
            failureType(
                    FailureType.COMPILATION_FAILURE,
                    "Source compilation or static checking clearly failed.",
                    "Current classifier emits this type for explicit compilation-failure wording, cannot-find-symbol style compiler output, TypeScript compiler diagnostics, Go file:line:column compiler diagnostics, and high-confidence C/C++ compiler or linker failures.",
                    "This points to code defects rather than transient infrastructure conditions."),
            failureType(
                    FailureType.TEST_ASSERTION_FAILURE,
                    "Tests failed because assertions did not match expectations.",
                    "Current classifier emits this type for explicit test assertion exception types and high-confidence test-runner failure summaries.",
                    "The MVP keeps test assertion failures non-retryable to avoid hiding product regressions."),
            failureType(
                    FailureType.DEPLOYMENT_FAILURE,
                    "A release or deployment step failed and may have partial side effects.",
                    "Reserved in the taxonomy; the current classifier does not emit this type yet.",
                    "Automatic retries here risk duplicate or partial deployments, so MVP keeps them off."),
            failureType(
                    FailureType.UNKNOWN,
                    "No deterministic rule matched with enough confidence.",
                    "Current classifier emits this fallback whenever no exception or message rule matches.",
                    "UNKNOWN stays non-retryable so Smart Retry never guesses its way into risky reruns."));

    private static final List<MatchedRuleDoc> MATCHED_RULES = List.of(
            matchedRule(
                    "pipeline-no-such-dsl-method",
                    FailureType.PIPELINE_LOGIC_FAILURE,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "No such DSL method",
                    DEFAULT_BEHAVIOR_NEVER_RETRY,
                    "This indicates the Jenkinsfile referenced a step or DSL method that Jenkins does not know how to run."),
            matchedRule(
                    "pipeline-no-such-property",
                    FailureType.PIPELINE_LOGIC_FAILURE,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "No such property",
                    DEFAULT_BEHAVIOR_NEVER_RETRY,
                    "This indicates the Pipeline script referenced a missing variable or property rather than hitting a transient infrastructure issue."),
            matchedRule(
                    "pipeline-script-security-rejected",
                    FailureType.PIPELINE_LOGIC_FAILURE,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "RejectedAccessException | Scripts not permitted to use | script approval",
                    DEFAULT_BEHAVIOR_NEVER_RETRY,
                    "Script-security rejections are deterministic Jenkins policy failures and should fail fast."),
            matchedRule(
                    "pipeline-groovy-startup-failed",
                    FailureType.PIPELINE_LOGIC_FAILURE,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "WorkflowScript/Jenkinsfile plus startup failed | MissingPropertyException | MissingMethodException | MultipleCompilationErrorsException",
                    DEFAULT_BEHAVIOR_NEVER_RETRY,
                    "This indicates the Pipeline Groovy script failed during parsing or evaluation rather than during an external transient operation."),
            matchedRule(
                    "compilation-failure",
                    FailureType.COMPILATION_FAILURE,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "Compilation failure | Compilation error",
                    DEFAULT_BEHAVIOR_NEVER_RETRY,
                    "Explicit compiler failure wording is treated as a deterministic code problem."),
            matchedRule(
                    "cannot-find-symbol",
                    FailureType.COMPILATION_FAILURE,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "cannot find symbol",
                    DEFAULT_BEHAVIOR_NEVER_RETRY,
                    "Missing symbols in compiler output indicate source or build-definition defects, not transient infrastructure."),
            matchedRule(
                    "typescript-compiler-error",
                    FailureType.COMPILATION_FAILURE,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "error TS<code>:",
                    DEFAULT_BEHAVIOR_NEVER_RETRY,
                    "TypeScript compiler diagnostic codes are treated as deterministic source or type-check failures rather than transient infrastructure issues."),
            matchedRule(
                    "go-compiler-error",
                    FailureType.COMPILATION_FAILURE,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "<file>.go:<line>:<column>: undefined: | cannot use | no required module provides package | imported and not used | syntax error:",
                    DEFAULT_BEHAVIOR_NEVER_RETRY,
                    "These are high-confidence Go compiler diagnostics that point to source, import, or module-definition defects."),
            matchedRule(
                    "c-family-compiler-error",
                    FailureType.COMPILATION_FAILURE,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "<file>.c/.cc/.cpp/.cxx/.h/.hpp:<line>[:<column>]: fatal error: | error:",
                    DEFAULT_BEHAVIOR_NEVER_RETRY,
                    "File-scoped C/C++ compiler diagnostics are treated as deterministic build failures rather than transient infrastructure conditions."),
            matchedRule(
                    "c-family-linker-error",
                    FailureType.COMPILATION_FAILURE,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "undefined reference to | collect2: error: ld returned N exit status | ld returned N exit status | clang: error: linker command failed",
                    DEFAULT_BEHAVIOR_NEVER_RETRY,
                    "Classic C/C++ linker failures indicate missing symbols or build-definition issues and should fail fast."),
            matchedRule(
                    "gradle-compile-task-failed",
                    FailureType.COMPILATION_FAILURE,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "Execution failed for task ':compileJava/:compileKotlin/...' with Compilation failed/error",
                    DEFAULT_BEHAVIOR_NEVER_RETRY,
                    "Gradle compile-task failures are only classified here when the log also contains an explicit compilation-failed signal, keeping generic task failures out of this rule."),
            matchedRule(
                    "java-compiler-error",
                    FailureType.COMPILATION_FAILURE,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "<file>.java:<line>[:<column>]: error:",
                    DEFAULT_BEHAVIOR_NEVER_RETRY,
                    "High-confidence javac diagnostics indicate deterministic source or dependency-definition defects rather than transient infrastructure issues."),
            matchedRule(
                    "kotlin-compiler-error",
                    FailureType.COMPILATION_FAILURE,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "e: <file>.kt: (line, column):",
                    DEFAULT_BEHAVIOR_NEVER_RETRY,
                    "High-confidence Kotlin compiler diagnostics indicate deterministic source or type-checking failures rather than transient infrastructure conditions."),
            matchedRule(
                    "test-opentest4j-assertion-failed",
                    FailureType.TEST_ASSERTION_FAILURE,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "org.opentest4j.AssertionFailedError",
                    DEFAULT_BEHAVIOR_NEVER_RETRY,
                    "This is the standard JUnit 5 assertion-failure signal and is treated as a deterministic test result."),
            matchedRule(
                    "test-junit-assertion-failed",
                    FailureType.TEST_ASSERTION_FAILURE,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "AssertionFailedError | ComparisonFailure",
                    DEFAULT_BEHAVIOR_NEVER_RETRY,
                    "Classic JUnit assertion mismatch types indicate product or test expectation problems rather than transient infrastructure."),
            matchedRule(
                    "test-runner-failures-summary",
                    FailureType.TEST_ASSERTION_FAILURE,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "There are/were test failures | FAILURES!!! | Tests run: N, Failures: M | Tests run: N, Errors: E",
                    DEFAULT_BEHAVIOR_NEVER_RETRY,
                    "High-confidence test runner summaries should fail fast instead of being hidden behind automatic retries."),
            matchedRule(
                    "test-pytest-failures-summary",
                    FailureType.TEST_ASSERTION_FAILURE,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "short test summary info with FAILED/ERROR | === N failed/error in Xs ===",
                    DEFAULT_BEHAVIOR_NEVER_RETRY,
                    "Pytest failure summaries are deterministic test-result signals and should fail fast instead of being retried."),
            matchedRule(
                    "test-gradle-failures-summary",
                    FailureType.TEST_ASSERTION_FAILURE,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "There were failing tests. See the report at: | N tests completed, M failed",
                    DEFAULT_BEHAVIOR_NEVER_RETRY,
                    "Gradle test-task summaries are deterministic test-result signals and should fail fast instead of being retried."),
            matchedRule(
                    "agent-kubernetes-pod-not-found",
                    FailureType.AGENT_LOST,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "Pod/pods <name> was deleted | not found | KubernetesClientException with pod not found",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "This captures high-confidence Kubernetes agent disappearance signals where the backing pod is gone before the build step can finish."),
            matchedRule(
                    "agent-kubernetes-evicted",
                    FailureType.AGENT_LOST,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "Evicted | node was low on resource | ephemeral-storage | MemoryPressure | DiskPressure",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "This captures Kubernetes eviction and resource-pressure failures where the agent was removed by the cluster rather than failing because of user code."),
            matchedRule(
                    "agent-remoting-channel-closed",
                    FailureType.AGENT_LOST,
                    "Exception/message context",
                    "ChannelClosedException | Cannot contact agent | was marked offline | connection was broken | agent not fully initialized",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "This captures Jenkins remoting and launcher failures where the agent channel closes or never finishes coming online, which is usually an infrastructure loss rather than a user-code defect."),
            matchedRule(
                    "agent-removed",
                    FailureType.AGENT_LOST,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "agent was removed | node was offline | channel closed | channel is closing | pod was deleted | evicted",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "The agent disappeared mid-run, which is a classic infrastructure-loss signal."),
            matchedRule(
                    "scm-remote-end-hung-up",
                    FailureType.SCM_TRANSIENT,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "remote end hung up unexpectedly",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "The remote SCM side dropped the connection unexpectedly, so a rerun may succeed."),
            matchedRule(
                    "scm-could-not-resolve-host",
                    FailureType.SCM_TRANSIENT,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "SCM context such as git checkout/fetch/clone/ls-remote plus could not resolve host",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "This only applies when the classifier sees clear SCM context around the host-resolution failure."),
            matchedRule(
                    "scm-transport-interrupted",
                    FailureType.SCM_TRANSIENT,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "SCM context such as git clone/fetch/checkout plus curl 56 | RPC failed | unexpected disconnect while reading sideband packet | early EOF | index-pack failed",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "This only applies when the classifier sees clear Git transport context around clone or fetch interruption signals."),
            matchedRule(
                    "scm-revision-not-found",
                    FailureType.SCM_CONFIGURATION_FAILURE,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "Couldn't find any revision to build | could not find any revision to build | verify the repository and branch configuration for this job | no revision to build",
                    DEFAULT_BEHAVIOR_NEVER_RETRY,
                    "This is a deterministic SCM target-selection error covering missing branches, tags, revisions, or commit SHAs."),
            matchedRule(
                    "scm-remote-branch-not-found",
                    FailureType.SCM_CONFIGURATION_FAILURE,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "remote branch ... not found in upstream origin | fatal: remote branch ... not found | branch ... not found in upstream origin",
                    DEFAULT_BEHAVIOR_NEVER_RETRY,
                    "This is a deterministic SCM target-selection error rather than a transient checkout failure."),
            matchedRule(
                    "network-could-not-resolve-host",
                    FailureType.NETWORK_TRANSIENT,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "could not resolve host",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "Generic host-resolution failures are treated as network problems unless SCM context is explicit."),
            matchedRule(
                    "network-timeout",
                    FailureType.NETWORK_TRANSIENT,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "read timed out | connect timed out | connection timed out",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "Timeouts often indicate a temporary service or network stall rather than a code problem."),
            matchedRule(
                    "network-http-5xx",
                    FailureType.NETWORK_TRANSIENT,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "502 Bad Gateway | 503 Service Unavailable | 504 Gateway Timeout | received/status code 502/503/504 | HTTP 502/503/504",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "Only explicit HTTP-style 5xx signals are treated as external service instability unless repository context is explicit."),
            matchedRule(
                    "network-python-gitlab-5xx",
                    FailureType.NETWORK_TRANSIENT,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "GitlabGetError plus 502/503/504 | GitLab is not responding",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "This only applies to python-gitlab GET failures that clearly report a transient GitLab 5xx response."),
            matchedRule(
                    "network-tls-handshake-timeout",
                    FailureType.NETWORK_TRANSIENT,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "tls handshake timeout",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "TLS handshake timeouts are treated as generic network instability unless repository context is explicit."),
            matchedRule(
                    "network-connection-refused",
                    FailureType.NETWORK_TRANSIENT,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "External-service context such as http/https URL, request url, dial tcp, docker pull, /v2/, or registry plus connection refused",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "This only applies when the classifier sees clear external-service context around a refused connection rather than generic refused wording."),
            matchedRule(
                    "network-connection-reset",
                    FailureType.NETWORK_TRANSIENT,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "External-service context such as http/https URL, request url, dial tcp, docker pull, /v2/, or registry plus connection reset",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "The connection dropped unexpectedly after work had already started, but generic reset wording alone is not enough without clear external-service context."),
            matchedRule(
                    "artifact-partial-download",
                    FailureType.ARTIFACT_REPO_TRANSIENT,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "Could not transfer artifact plus .jar.part plus No such file or directory",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "This usually means Maven was in the middle of downloading from the artifact repository when the transfer broke."),
            matchedRule(
                    "artifact-http-5xx",
                    FailureType.ARTIFACT_REPO_TRANSIENT,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "Artifact repository context plus explicit HTTP-style 502/503/504 signal",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "This only applies when the classifier sees repository-specific context around the transient 5xx response."),
            matchedRule(
                    "artifact-tls-handshake-timeout",
                    FailureType.ARTIFACT_REPO_TRANSIENT,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "Artifact repository context plus tls handshake timeout",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "This only applies when the classifier sees repository-specific context around the TLS failure."),
            matchedRule(
                    "artifact-connection-refused",
                    FailureType.ARTIFACT_REPO_TRANSIENT,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "Artifact repository context plus connection refused",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "This only applies when the classifier sees repository-specific context around the refused connection, so generic service failures do not get misclassified as repository outages."),
            matchedRule(
                    "artifact-connection-reset",
                    FailureType.ARTIFACT_REPO_TRANSIENT,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "Artifact repository context plus connection reset",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "This only applies when the classifier sees repository-specific context around the dropped connection, which makes it a better artifact-repository match than the generic network bucket."),
            matchedRule(
                    "identity-provider-ldap-reauthentication-failed",
                    FailureType.IDENTITY_PROVIDER_TRANSIENT,
                    TRIGGER_KIND_MESSAGE_PATTERN,
                    "LDAP reauthentication wording plus HTTP-style 401 signal",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "This only applies to narrow LDAP identity-provider reauthentication failures and does not treat generic 401 responses as transient."),
            matchedRule(
                    "flow-interrupted",
                    FailureType.USER_ABORT,
                    TRIGGER_KIND_EXCEPTION_TYPE,
                    "FlowInterruptedException",
                    DEFAULT_BEHAVIOR_NEVER_RETRY,
                    "This is treated as an intentional stop signal, not a flaky infrastructure event."),
            matchedRule(
                    "socket-timeout",
                    FailureType.NETWORK_TRANSIENT,
                    TRIGGER_KIND_EXCEPTION_TYPE,
                    "SocketTimeoutException",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "Socket timeouts are mapped directly to transient network instability."),
            matchedRule(
                    "connect-exception",
                    FailureType.NETWORK_TRANSIENT,
                    TRIGGER_KIND_EXCEPTION_TYPE,
                    "ConnectException",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "The target service could not be reached at connect time and may recover on a later attempt."),
            matchedRule(
                    "socket-exception",
                    FailureType.NETWORK_TRANSIENT,
                    TRIGGER_KIND_EXCEPTION_TYPE,
                    "SocketException with connection reset or broken pipe",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "Only the clearly transient socket break variants are accepted here."),
            matchedRule(
                    "eof",
                    FailureType.NETWORK_TRANSIENT,
                    TRIGGER_KIND_EXCEPTION_TYPE,
                    "EOFException",
                    DEFAULT_BEHAVIOR_RETRY_CANDIDATE,
                    "Unexpected end-of-stream often means the remote side dropped the connection mid-flight."));

    private SmartRetryReferenceCatalog() {}

    public static List<FailureTypeDoc> failureTypes() {
        return FAILURE_TYPES;
    }

    public static List<MatchedRuleDoc> matchedRules() {
        return MATCHED_RULES;
    }

    public static List<MatchedRuleGroup> matchedRuleGroups() {
        LinkedHashMap<String, FailureTypeDoc> failureTypesByName = new LinkedHashMap<>();
        LinkedHashMap<String, GroupBuilder> grouped = new LinkedHashMap<>();
        for (FailureTypeDoc type : FAILURE_TYPES) {
            failureTypesByName.put(type.getName(), type);
            grouped.put(type.getName(), new GroupBuilder(type.getName(), type.getAnchorId(), type.getMeaning()));
        }
        for (MatchedRuleDoc rule : MATCHED_RULES) {
            GroupBuilder builder = grouped.get(rule.getFailureTypeName());
            if (builder == null) {
                FailureTypeDoc type = failureTypesByName.get(rule.getFailureTypeName());
                builder = new GroupBuilder(
                        rule.getFailureTypeName(),
                        rule.getFailureTypeAnchorId(),
                        type == null ? "" : type.getMeaning());
                grouped.put(rule.getFailureTypeName(), builder);
            }
            builder.rules.add(rule);
        }

        List<MatchedRuleGroup> groups = new ArrayList<>();
        for (Map.Entry<String, GroupBuilder> entry : grouped.entrySet()) {
            GroupBuilder builder = entry.getValue();
            if (!builder.rules.isEmpty()) {
                groups.add(new MatchedRuleGroup(
                        "group-" + slugify(builder.failureTypeName),
                        builder.failureTypeName,
                        builder.failureTypeAnchorId,
                        builder.summary,
                        Collections.unmodifiableList(new ArrayList<>(builder.rules))));
            }
        }
        return Collections.unmodifiableList(groups);
    }

    public static List<CustomRuleDoc> customRules() {
        SmartRetryGlobalConfiguration cfg = SmartRetryGlobalConfiguration.get();
        if (cfg == null) {
            return List.of();
        }
        List<CustomRuleDoc> docs = new ArrayList<>();
        for (CustomClassificationRule rule : cfg.getCustomClassificationRules()) {
            docs.add(new CustomRuleDoc(
                    slugify(rule.getName()),
                    rule.getName(),
                    rule.getPattern(),
                    rule.getFailureType().name(),
                    rule.isEnabled(),
                    rule.getDescription()));
        }
        return Collections.unmodifiableList(docs);
    }

    private static FailureTypeDoc failureType(
            FailureType type, String meaning, String currentImplementation, String rationale) {
        return new FailureTypeDoc(
                "failure-type-" + slugify(type.name()),
                type.name(),
                meaning,
                currentImplementation,
                retryPolicyLabel(BuiltInProfiles.conservative()
                        .getRetryableFailureTypes()
                        .contains(type)),
                retryPolicyLabel(
                        BuiltInProfiles.infra().getRetryableFailureTypes().contains(type)),
                customProfileLabel(type),
                rationale);
    }

    private static MatchedRuleDoc matchedRule(
            String name,
            FailureType failureType,
            String triggerKind,
            String triggerHint,
            String defaultBehavior,
            String rationale) {
        boolean disableable = SUPPORTED_DISABLED_BUILT_IN_RULE_IDS.contains(name);
        return new MatchedRuleDoc(
                "rule-" + slugify(name),
                name,
                failureType.name(),
                "failure-type-" + slugify(failureType.name()),
                triggerKind,
                triggerHint,
                defaultBehavior,
                disableable ? DISABLEABLE_YES : "No",
                rationale);
    }

    private static String retryPolicyLabel(boolean retryable) {
        return retryable ? PROFILE_BEHAVIOR_RETRY_ALLOWED : PROFILE_BEHAVIOR_NO_RETRY;
    }

    private static Badge retryPolicyBadge(boolean retryable, String profileName) {
        if (retryable) {
            return new Badge(
                    PROFILE_BEHAVIOR_RETRY_ALLOWED,
                    "The " + profileName + " profile may retry this failure type.",
                    Badge.Severity.INFO);
        }
        return new Badge(
                PROFILE_BEHAVIOR_NO_RETRY,
                "The " + profileName + " profile does not retry this failure type.",
                Badge.Severity.WARNING);
    }

    private static Badge configurableBadge(boolean configurable) {
        if (configurable) {
            return new Badge(
                    CUSTOM_BEHAVIOR_CONFIGURABLE,
                    "Custom profiles may choose to retry this failure type.",
                    Badge.Severity.INFO);
        }
        return new Badge(
                PROFILE_BEHAVIOR_NO_RETRY,
                "Custom profiles do not expose this failure type as retryable.",
                Badge.Severity.WARNING);
    }

    private static Badge yesNoBadge(boolean enabled, String enabledText, String disabledText) {
        if (enabled) {
            return new Badge(enabledText, enabledText, Badge.Severity.INFO);
        }
        return new Badge(disabledText, disabledText, Badge.Severity.WARNING);
    }

    private static String customProfileLabel(FailureType type) {
        return CustomProfileSettings.supportedRetryableFailureTypes().contains(type)
                ? CUSTOM_BEHAVIOR_CONFIGURABLE
                : PROFILE_BEHAVIOR_NO_RETRY;
    }

    private static String slugify(String value) {
        return value.toLowerCase().replace('_', '-');
    }

    private static final class GroupBuilder {
        private final String failureTypeName;
        private final String failureTypeAnchorId;
        private final String summary;
        private final List<MatchedRuleDoc> rules = new ArrayList<>();

        private GroupBuilder(String failureTypeName, String failureTypeAnchorId, String summary) {
            this.failureTypeName = failureTypeName;
            this.failureTypeAnchorId = failureTypeAnchorId;
            this.summary = summary;
        }
    }

    public static final class FailureTypeDoc {
        private final String anchorId;
        private final String name;
        private final String meaning;
        private final String currentImplementation;
        private final String conservativeBehavior;
        private final String infraBehavior;
        private final String customBehavior;
        private final String rationale;

        public FailureTypeDoc(
                String anchorId,
                String name,
                String meaning,
                String currentImplementation,
                String conservativeBehavior,
                String infraBehavior,
                String customBehavior,
                String rationale) {
            this.anchorId = anchorId;
            this.name = name;
            this.meaning = meaning;
            this.currentImplementation = currentImplementation;
            this.conservativeBehavior = conservativeBehavior;
            this.infraBehavior = infraBehavior;
            this.customBehavior = customBehavior;
            this.rationale = rationale;
        }

        public String getAnchorId() {
            return anchorId;
        }

        public String getName() {
            return name;
        }

        public String getMeaning() {
            return meaning;
        }

        public String getCurrentImplementation() {
            return currentImplementation;
        }

        public String getConservativeBehavior() {
            return conservativeBehavior;
        }

        public String getInfraBehavior() {
            return infraBehavior;
        }

        public String getCustomBehavior() {
            return customBehavior;
        }

        public String getRationale() {
            return rationale;
        }

        public Badge getConservativeBadge() {
            return retryPolicyBadge(
                    PROFILE_BEHAVIOR_RETRY_ALLOWED.equals(conservativeBehavior), BuiltInProfiles.PROFILE_CONSERVATIVE);
        }

        public Badge getInfraBadge() {
            return retryPolicyBadge(
                    PROFILE_BEHAVIOR_RETRY_ALLOWED.equals(infraBehavior), BuiltInProfiles.PROFILE_INFRA);
        }

        public Badge getCustomBadge() {
            return configurableBadge(CUSTOM_BEHAVIOR_CONFIGURABLE.equals(customBehavior));
        }

        public boolean isDefaultExpanded() {
            return "AGENT_LOST".equals(name) || "SCM_TRANSIENT".equals(name) || "NETWORK_TRANSIENT".equals(name);
        }

        public String getMatchedRuleGroupAnchorId() {
            return "group-" + slugify(name);
        }
    }

    public static final class MatchedRuleDoc {
        private final String anchorId;
        private final String name;
        private final String failureTypeName;
        private final String failureTypeAnchorId;
        private final String triggerKind;
        private final String triggerHint;
        private final String defaultBehavior;
        private final String disableable;
        private final String rationale;

        public MatchedRuleDoc(
                String anchorId,
                String name,
                String failureTypeName,
                String failureTypeAnchorId,
                String triggerKind,
                String triggerHint,
                String defaultBehavior,
                String disableable,
                String rationale) {
            this.anchorId = anchorId;
            this.name = name;
            this.failureTypeName = failureTypeName;
            this.failureTypeAnchorId = failureTypeAnchorId;
            this.triggerKind = triggerKind;
            this.triggerHint = triggerHint;
            this.defaultBehavior = defaultBehavior;
            this.disableable = disableable;
            this.rationale = rationale;
        }

        public String getAnchorId() {
            return anchorId;
        }

        public String getName() {
            return name;
        }

        public String getFailureTypeName() {
            return failureTypeName;
        }

        public String getFailureTypeAnchorId() {
            return failureTypeAnchorId;
        }

        public String getTriggerKind() {
            return triggerKind;
        }

        public String getTriggerHint() {
            return triggerHint;
        }

        public String getDefaultBehavior() {
            return defaultBehavior;
        }

        public String getDisableable() {
            return disableable;
        }

        public String getRationale() {
            return rationale;
        }

        public Badge getDefaultBehaviorBadge() {
            return retryPolicyBadge(DEFAULT_BEHAVIOR_RETRY_CANDIDATE.equals(defaultBehavior), "classifier");
        }

        public Badge getDisableableBadge() {
            return yesNoBadge("Yes".equals(disableable), "Disableable", "Fixed");
        }

        public boolean isTriggerCode() {
            return TRIGGER_KIND_MESSAGE_PATTERN.equals(triggerKind) || TRIGGER_KIND_EXCEPTION_TYPE.equals(triggerKind);
        }

        public String getTriggerPreview() {
            if (triggerHint.length() <= 88) {
                return triggerHint;
            }
            return triggerHint.substring(0, 85) + "...";
        }
    }

    public static final class MatchedRuleGroup {
        private final String anchorId;
        private final String failureTypeName;
        private final String failureTypeAnchorId;
        private final String summary;
        private final List<MatchedRuleDoc> rules;

        public MatchedRuleGroup(
                String anchorId,
                String failureTypeName,
                String failureTypeAnchorId,
                String summary,
                List<MatchedRuleDoc> rules) {
            this.anchorId = anchorId;
            this.failureTypeName = failureTypeName;
            this.failureTypeAnchorId = failureTypeAnchorId;
            this.summary = summary;
            this.rules = rules;
        }

        public String getAnchorId() {
            return anchorId;
        }

        public String getFailureTypeName() {
            return failureTypeName;
        }

        public String getFailureTypeAnchorId() {
            return failureTypeAnchorId;
        }

        public List<MatchedRuleDoc> getRules() {
            return rules;
        }

        public String getSummary() {
            return summary;
        }

        public int getRuleCount() {
            return rules.size();
        }

        public boolean isDefaultExpanded() {
            return "AGENT_LOST".equals(failureTypeName) || "SCM_TRANSIENT".equals(failureTypeName);
        }
    }

    public static final class CustomRuleDoc {
        private final String anchorId;
        private final String name;
        private final String pattern;
        private final String failureTypeName;
        private final boolean enabled;
        private final String description;

        public CustomRuleDoc(
                String anchorId,
                String name,
                String pattern,
                String failureTypeName,
                boolean enabled,
                String description) {
            this.anchorId = anchorId;
            this.name = name;
            this.pattern = pattern;
            this.failureTypeName = failureTypeName;
            this.enabled = enabled;
            this.description = description;
        }

        public String getAnchorId() {
            return anchorId;
        }

        public String getName() {
            return name;
        }

        public String getPattern() {
            return pattern;
        }

        public String getFailureTypeName() {
            return failureTypeName;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public String getDescription() {
            return description;
        }
    }
}

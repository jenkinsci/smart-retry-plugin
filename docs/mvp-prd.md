# Smart Retry MVP PRD

## 1. Summary

Smart Retry is a Jenkins Pipeline plugin that retries only failures that look like transient infrastructure problems, instead of blindly rerunning any failed block.

The initial product shape is a Pipeline step:

```groovy
smartRetry(profile: 'infra', maxRetries: 2, backoff: 'exponential') {
  sh 'mvn -B test'
}
```

The core value proposition is:

- reduce manual rebuilds caused by transient CI failures
- avoid blind retries on deterministic failures such as compilation errors
- make retry decisions visible and easy to trust in logs and build UI

## 2. Problem Statement

Teams running Jenkins on Kubernetes or other ephemeral agents frequently see build failures caused by temporary infrastructure issues:

- build agent or pod disappears during execution
- `checkout scm` fails due to network or remote Git instability
- Maven, npm, PyPI, Docker registry, or Artifactory is temporarily unavailable
- controller-to-agent channel drops unexpectedly

These failures often succeed on a manual rebuild. Jenkins' built-in plain `retry {}` step helps, but it is too coarse because it retries everything, including:

- source code compilation failures
- Pipeline script mistakes
- stable test assertion failures
- non-idempotent deployment steps

Jenkins also offers `retry` with `conditions`, which is useful for selected infrastructure cases such as agent loss and resumability-related failures. Smart Retry should complement that capability with profile-driven policy, broader high-confidence transient failure classification, and more explicit retry reasoning for build users.

## 3. Goals

The MVP should:

- provide a `smartRetry { ... }` Pipeline step
- classify a small set of high-value transient failures
- retry only failures allowed by the active profile
- support `maxRetries` and simple backoff strategies
- explain retry decisions in console logs
- expose retry summary data on the build page
- support a small Jenkins global configuration surface for safe defaults in V1, with broader profile authoring deferred if needed

## 4. Non-Goals

The MVP should not:

- automatically wrap every Pipeline stage or build
- use AI-based failure classification
- detect flaky tests from historical trends
- retry compilation errors, assertion failures, or Pipeline logic errors
- optimize deployment or release retries by default
- depend on persistent telemetry or machine learning

## 5. Target Users

Primary target users:

- Jenkins administrators running Kubernetes-based agents
- platform teams using ephemeral cloud agents
- CI teams affected by SCM, network, or artifact repository instability

Secondary target users:

- teams already using shared-library retry wrappers, but wanting central configuration and better visibility

Not a first-priority audience:

- small or static Jenkins installations with stable agents
- teams whose primary build pain is code quality rather than infrastructure noise

## 6. Product Positioning

One-line positioning:

> Retry only the failures worth retrying.

What makes it different from built-in Jenkins retry steps:

- failure-aware rather than unconditional
- broader transient failure coverage than agent-focused built-in retry conditions alone
- retries the right failures instead of every failure
- safer defaults
- profile-driven behavior
- better observability

## 7. MVP User Stories

1. As a Jenkins administrator, I want to define a default retry strategy so teams can adopt Smart Retry without duplicating rules in every Jenkinsfile.
2. As a Pipeline author, I want to wrap a flaky infrastructure-sensitive step in `smartRetry` so transient failures recover automatically.
3. As a build investigator, I want to see why Smart Retry did or did not retry so I can trust the plugin's behavior.
4. As a platform team, I want deterministic failures such as compilation errors to fail fast and never be retried by default.

## 8. DSL

### 8.1 Minimal DSL

```groovy
smartRetry(
  profile: 'infra',
  maxRetries: 2,
  backoff: 'exponential',
  initialDelaySeconds: 10
) {
  sh 'mvn -B test'
}
```

### 8.2 Initial Parameters

- `profile`
- `maxRetries`
- `backoff`
- `initialDelaySeconds`

### 8.3 Possible V1.1 Parameters

- `retryOn`
- `skipOn`

These should not be required for the first usable release.

Current product direction:

- named custom profiles should cover the first pilot and MVP release without requiring step-local `retryOn` and `skipOn`
- if `retryOn` and `skipOn` are added later, they should operate on classified `FailureType` values rather than raw log text
- if they are added later, they should apply as policy deltas on top of the selected profile:
  - `retryOn` adds retryable `FailureType` values to the profile allowlist
  - `skipOn` removes `FailureType` values from the profile allowlist

## 9. Failure Taxonomy

The taxonomy should stay intentionally small in the MVP. Accuracy matters more than breadth.

Current V1 implementation note:

- the classifier currently emits `AGENT_LOST`, `SCM_TRANSIENT`, `NETWORK_TRANSIENT`, `ARTIFACT_REPO_TRANSIENT`, `IDENTITY_PROVIDER_TRANSIENT`, `SCM_CONFIGURATION_FAILURE`, `PIPELINE_LOGIC_FAILURE`, `COMPILATION_FAILURE`, `TEST_ASSERTION_FAILURE`, `USER_ABORT`, and `UNKNOWN`
- `DEPLOYMENT_FAILURE` remains part of the stable taxonomy, but current V1 rules do not emit it yet

### 9.1 Retryable-by-Policy Candidates

#### `AGENT_LOST`

Meaning:

- the agent, pod, node, or remoting channel disappeared during execution

Typical signals:

- `ChannelClosedException`
- `Agent was removed`
- `node was offline`
- `pod was deleted`
- `Evicted`

Default classification:

- retryable candidate

#### `SCM_TRANSIENT`

Meaning:

- source checkout or fetch failed because of temporary SCM/network instability

Typical signals:

- `remote end hung up unexpectedly`
- `connection timed out`
- `git fetch` / `git clone` / `checkout` context plus `could not resolve host`
- `git fetch` / `git clone` / `checkout` context plus `curl 56`, `RPC failed`, `unexpected disconnect while reading sideband packet`, `early EOF`, or `index-pack failed`
- `connection reset`
- explicit Git/SCM HTTP 502/503/504 style failures

Default classification:

- retryable candidate

#### `SCM_CONFIGURATION_FAILURE`

Meaning:

- the requested SCM revision, branch, tag, commit, or checkout target does not exist

Typical signals:

- `Couldn't find any revision to build`
- `Could not find any revision to build`
- `Verify the repository and branch configuration for this job`
- `No revision to build`
- `Remote branch ... not found in upstream origin`
- `fatal: Remote branch ... not found`
- `branch ... not found in upstream origin`

Default classification:

- never retry

#### `NETWORK_TRANSIENT`

Meaning:

- the step failed because an external service could not be reached reliably

Typical signals:

- `could not resolve host`
- `Read timed out`
- `Connect timed out`
- external-service context plus `connect: connection refused`
- external-service context plus `connection reset by peer`
- `Broken pipe`
- `Connection reset`
- explicit HTTP-style `502 Bad Gateway`, `503 Service Unavailable`, `504 Gateway Timeout`, or `status code 502/503/504`
- `tls handshake timeout`

Default classification:

- retryable candidate

#### `ARTIFACT_REPO_TRANSIENT`

Meaning:

- a dependency or artifact repository was temporarily unavailable

Typical signals:

- `Could not transfer artifact ... .jar.part (No such file or directory)`
- repository context plus `Received status code 502/503/504 from server`
- repository context plus `503 Service Unavailable`
- repository context plus `504 Gateway Timeout`
- repository context plus `TLS handshake timeout`

Default classification:

- retryable candidate

#### `IDENTITY_PROVIDER_TRANSIENT`

Meaning:

- an external identity provider failed during authentication or reauthentication in a way that is known to be environment-driven rather than a generic permission denial

Typical signals:

- LDAP reauthentication wording plus HTTP-style `401`
- `Can't reauthenticate LDAP ...` with clear identity-provider context

Default classification:

- retryable candidate

### 9.2 Default Non-Retryable Classes

#### `USER_ABORT`

Meaning:

- the build was intentionally aborted by a user or explicit interruption path

Default classification:

- never retry

#### `PIPELINE_LOGIC_FAILURE`

Meaning:

- Jenkinsfile or Pipeline script logic is broken

Examples:

- Groovy script errors
- missing step or DSL misuse
- script-security rejection

Default classification:

- never retry

#### `COMPILATION_FAILURE`

Meaning:

- source code compilation, static analysis, or type checking clearly failed

Examples:

- `Compilation failure`
- `cannot find symbol`
- Java compiler error such as `<file>.java:<line>: error:`
- Kotlin compiler error such as `e: <file>.kt: (line, column):`
- TypeScript compile error
- Go build error
- C/C++ compiler or linker error
- Gradle `:compileJava` or `:compileKotlin` task with explicit `Compilation failed/error`

Default classification:

- never retry

#### `TEST_ASSERTION_FAILURE`

Meaning:

- tests failed because assertions did not match expectations

Examples:

- JUnit/OpenTest4J assertion mismatch
- Maven Surefire failure or error summary
- pytest `short test summary info` with `FAILED` or `ERROR`
- pytest terminal summary such as `=== 1 failed, 441 passed in 1196.37s ===`
- Gradle test summary such as `4 tests completed, 1 failed`
- Gradle `There were failing tests. See the report at:`

Default classification:

- never retry in MVP

#### `DEPLOYMENT_FAILURE`

Meaning:

- a release or deployment step failed and may have partial side effects

Default classification:

- never retry in MVP

#### `UNKNOWN`

Meaning:

- no rule matched with enough confidence

Default classification:

- never retry

## 10. Classification Rules

The classifier should use two inputs:

- exception types
- console log or error message patterns

Note:

- some Pipeline steps (for example `sh`) may not include the most useful failure text in the thrown exception message
- the MVP classifier may use a bounded, attempt-scoped fragment of the build console log as `messageContext` to match common transient patterns (for example `could not resolve host`)

Decision principles:

- explicit non-retryable categories take priority over retryable categories
- if multiple rules match, prefer the most deterministic category
- unmatched failures become `UNKNOWN`
- `UNKNOWN` must remain non-retryable by default

Suggested priority order:

1. `USER_ABORT`
2. `DEPLOYMENT_FAILURE`
3. `PIPELINE_LOGIC_FAILURE`
4. `COMPILATION_FAILURE`
5. `TEST_ASSERTION_FAILURE`
6. `AGENT_LOST`
7. `SCM_TRANSIENT`
8. `ARTIFACT_REPO_TRANSIENT`
9. `NETWORK_TRANSIENT`
10. `IDENTITY_PROVIDER_TRANSIENT`
11. `UNKNOWN`

## 11. Profiles

### 11.1 `conservative`

Intent:

- lowest-risk adoption path

Allowed classes:

- `AGENT_LOST`
- `SCM_TRANSIENT`

Suggested defaults:

- `maxRetries = 1`
- `backoff = fixed`
- `initialDelaySeconds = 10`

Recommended use:

- default global profile

### 11.2 `infra`

Intent:

- optimize for Kubernetes and cloud CI reliability problems

Allowed classes:

- `AGENT_LOST`
- `SCM_TRANSIENT`
- `NETWORK_TRANSIENT`
- `ARTIFACT_REPO_TRANSIENT`
- `IDENTITY_PROVIDER_TRANSIENT`

Suggested defaults:

- `maxRetries = 1`
- `backoff = fixed`
- `initialDelaySeconds = 10`

Recommended use:

- teams with ephemeral agents and external dependency volatility

### 11.3 `custom`

Intent:

- allow platform teams to define named retry-policy variants without weakening built-in classifier behavior

Characteristics:

- supports multiple named custom profiles
- each profile defines which transient `FailureType` values are retryable
- shared retry timing defaults still come from global configuration unless the step overrides them
- profile resolution should be exact and fail fast for unknown names

Current V1 implementation note:

- built-in profiles remain fixed as `conservative` and `infra`
- administrators can define any number of named custom profiles, including a profile literally named `custom` if they want that shorthand
- current V1 custom profiles only configure retryable transient `FailureType` allowlists
- current V1 also supports constrained global custom classification rules for narrow transient-only regex-to-`FailureType` mappings

### 11.4 Constrained Custom Rule Design

Custom rules should be intentionally narrow:

- built-in deterministic rules remain the baseline and cannot be deleted
- custom rules should be additive, not a general-purpose rules engine
- custom rules should primarily capture environment-specific transient patterns that the built-in rules do not know yet
- custom rules are part of classification, not retry policy

Suggested first custom-rule fields:

- `name`
- `pattern`
- `failureType`
- `enabled`
- optional human-readable `description`

Suggested adjacent built-in rule override fields:

- `disabledBuiltInRules`

Suggested first allowed target types:

- `AGENT_LOST`
- `SCM_TRANSIENT`
- `NETWORK_TRANSIENT`
- `ARTIFACT_REPO_TRANSIENT`
- `IDENTITY_PROVIDER_TRANSIENT`

Safety boundaries:

- custom rules must not make `USER_ABORT`, `PIPELINE_LOGIC_FAILURE`, `COMPILATION_FAILURE`, `TEST_ASSERTION_FAILURE`, `DEPLOYMENT_FAILURE`, or `UNKNOWN` retryable
- custom rules should not replace built-in hard-stop behavior
- broad rule-authoring power is less important than explainability and safe defaults
- built-in hard-stop rules should not be disableable from configuration
- built-in retryable rules may be selectively disabled by stable rule id when operators need to narrow behavior without turning off an entire `FailureType`

Responsibility boundary:

- `disabledBuiltInRules` disables specific built-in rule ids without changing the meaning of other rules in the same `FailureType`
- custom rules decide how raw exception/message patterns map into a `FailureType`
- `retryOn` and `skipOn`, if introduced later, should decide which already-classified `FailureType` values a selected profile retries or blocks
- `retryOn` and `skipOn` should not accept raw regex or free-form message snippets

## 12. Retry Decision Flow

1. Execute the body block.
2. On failure, capture the thrown exception and relevant log or message context.
3. Run the failure classifier.
4. If the result is a hard non-retryable class, fail immediately.
5. If the result is allowed by the active profile and the attempt count has not been exhausted, sleep according to backoff and rerun the body.
6. Otherwise fail immediately.
7. Record the attempt result and classification for later display.

## 13. Observability and UX

### 13.1 Console Logging

Each retry decision should print:

- current attempt number
- matched failure class
- retry or no-retry decision
- wait duration before the next attempt
- final outcome when retries stop

Example:

```text
[smartRetry] begin attempt=1
[smartRetry] attempt=1 profile=conservative classified=SCM_TRANSIENT retryCandidate=true decision=RETRY nextAttempt=2 delayMillis=10000 reason="Retryable transient failure: SCM_TRANSIENT"
[smartRetry] begin attempt=2
[smartRetry] attempt=2 profile=conservative classified=SCM_TRANSIENT retryCandidate=true decision=FAIL nextAttempt=0 delayMillis=0 reason="Retry attempts exhausted (maxRetries=1)"
```

### 13.2 Build Summary

Each build should expose a summary showing:

- active profile
- total attempts
- retry reasons
- final status

Possible representation:

- a `RunAction` summary on the build page
- a lightweight summary box rather than a complex dashboard

## 14. Global Configuration

Current V1 global configuration supports:

- default profile selection by built-in or custom profile name
- bounded console-log context depth for classification
- shared retry count, backoff, and initial delay defaults for all profiles
- named custom profiles with configurable retryable transient `FailureType` values
- built-in rule disabling by stable rule id
- constrained custom classification rules that map narrow environment-specific regex patterns to supported transient `FailureType` values

Follow-on configuration, if needed after pilot feedback, may support:

- profile-relative `retryOn` and `skipOn`

Suggested first custom-rule evaluation order:

1. built-in hard-stop exception rules
2. custom rules
3. built-in rule disable check for message-based rules
4. built-in non-retryable message rules
5. built-in retryable rules
6. `UNKNOWN`

The V1 UI should stay simple. Reliability and clarity are more important than advanced rule authoring.

## 15. Safety Rules

The plugin should intentionally bias toward not retrying when uncertain.

Hard safety rules for the MVP:

- `UNKNOWN` is not retryable
- `COMPILATION_FAILURE` is not retryable
- `PIPELINE_LOGIC_FAILURE` is not retryable
- `USER_ABORT` is not retryable
- deployment-like steps are not a primary target

Documentation should clearly state that the plugin is best suited for idempotent CI steps.

## 16. Technical Design Sketch

Suggested core classes:

- `SmartRetryStep`
- `SmartRetryStepExecution`
- `FailureClassifier`
- `FailureClassification`
- `RetryPolicy`
- `RetryDecision`
- `SmartRetryGlobalConfiguration`
- `SmartRetryRunAction`

Suggested responsibilities:

- `SmartRetryStep`: Pipeline DSL parameters
- `SmartRetryStepExecution`: executes the body, intercepts failures, and schedules retries
- `FailureClassifier`: maps errors to a failure class
- `RetryPolicy`: applies profile and rule logic
- `RetryDecision`: immutable decision result with reason and delay
- `SmartRetryRunAction`: build-page summary and stored attempt history

## 17. Test Plan

The initial test plan should cover:

- `AGENT_LOST` retry path
- `SCM_TRANSIENT` retry path
- `COMPILATION_FAILURE` no-retry behavior
- `UNKNOWN` no-retry behavior
- max-retry exhaustion
- fixed backoff selection
- exponential backoff selection
- default profile global configuration plus Jenkinsfile override behavior
- build summary rendering and stored attempt data

Recommended test layers:

- unit tests for classifier and policy
- Pipeline integration tests for step behavior
- configuration round-trip tests for global settings

## 18. Success Metrics

Signals that the MVP is working:

- builds successfully recover from transient failures after retry
- administrators can explain why a retry happened
- deterministic failures do not get retried
- pilot teams keep Smart Retry enabled after trial usage

Adoption-oriented metrics:

- number of controllers installing the plugin
- number of jobs using `smartRetry`
- retry-trigger rate
- retry-to-success rate
- complaint rate for false-positive retries

## 19. Release Plan

### Phase 1: MVP

- `smartRetry` step
- fixed built-in profiles `conservative` and `infra`
- named custom profiles
- small failure taxonomy
- console logging
- build summary action
- global configuration for `defaultProfile`, bounded console context, shared retry defaults, named custom profiles, built-in rule disabling, and constrained custom classification rules for supported transient `FailureType` targets

### Phase 2

- richer rule authoring and rule-debugging UX
- `retryOn` and `skipOn`

Expected layering in Phase 2:

- built-in rule disablement narrows existing classifier behavior by stable rule id
- custom rules extend classification
- custom profile settings, including possible profile-relative `retryOn` and `skipOn`, extend policy

### Phase 3

- flaky test integration
- richer reporting
- deeper integration with Kubernetes and SCM failure patterns

## 20. Open Questions

- Should the first implementation target only Pipeline or also expose any Freestyle integration?
- How much log context can be safely captured without making classification brittle or expensive?
- After pilot feedback, is there any recurring need for step-local policy deltas that named custom profiles cannot cover cleanly?

Resolved questions:

- Constrained custom classification rules are part of the pilot V1 line. They remain additive, transient-only, and subordinate to built-in hard-stop behavior. Follow-up evaluation should focus on whether broader rule authoring UX or step-local policy deltas are still needed after pilot feedback.

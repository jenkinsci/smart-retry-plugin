package io.jenkins.plugins.smart_retry.config;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.Descriptor.FormException;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.smart_retry.classify.DeterministicFailureClassifier;
import io.jenkins.plugins.smart_retry.policy.BackoffStrategy;
import io.jenkins.plugins.smart_retry.policy.BuiltInProfiles;
import io.jenkins.plugins.smart_retry.policy.RuntimeSettings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

@Extension
public final class SmartRetryGlobalConfiguration extends GlobalConfiguration {

    private static final String DEFAULT_PROFILE = BuiltInProfiles.PROFILE_CONSERVATIVE;
    private static final String BACKOFF_FIXED = BuiltInProfiles.BACKOFF_FIXED;
    private static final String BACKOFF_EXPONENTIAL = BuiltInProfiles.BACKOFF_EXPONENTIAL;

    private String defaultProfile = DEFAULT_PROFILE;
    private int consoleContextLines = 200;
    private int maxRetries = BuiltInProfiles.DEFAULT_MAX_RETRIES;
    private String backoff = BACKOFF_FIXED;
    private int initialDelaySeconds = BuiltInProfiles.DEFAULT_INITIAL_DELAY_SECONDS;
    private List<CustomProfileSettings> customProfiles = new ArrayList<>();
    private String disabledBuiltInRules = "";
    private List<CustomClassificationRule> customClassificationRules = new ArrayList<>();

    public SmartRetryGlobalConfiguration() {
        load();
    }

    public static SmartRetryGlobalConfiguration get() {
        return GlobalConfiguration.all().get(SmartRetryGlobalConfiguration.class);
    }

    private static void checkAdministerPermission() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
    }

    public String getDefaultProfile() {
        return defaultProfile;
    }

    @DataBoundSetter
    public void setDefaultProfile(@CheckForNull String defaultProfile) {
        this.defaultProfile = normalize(defaultProfile, DEFAULT_PROFILE);
    }

    public int getConsoleContextLines() {
        return consoleContextLines;
    }

    @DataBoundSetter
    public void setConsoleContextLines(int consoleContextLines) {
        this.consoleContextLines = Math.max(0, consoleContextLines);
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    @DataBoundSetter
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = Math.max(0, maxRetries);
    }

    public String getBackoff() {
        return backoff;
    }

    @DataBoundSetter
    public void setBackoff(@CheckForNull String backoff) {
        this.backoff = normalizeBackoff(backoff, defaultBackoffName());
    }

    public int getInitialDelaySeconds() {
        return initialDelaySeconds;
    }

    @DataBoundSetter
    public void setInitialDelaySeconds(int initialDelaySeconds) {
        this.initialDelaySeconds = Math.max(0, initialDelaySeconds);
    }

    public List<CustomProfileSettings> getCustomProfiles() {
        return new ArrayList<>(customProfiles());
    }

    @DataBoundSetter
    public void setCustomProfiles(@CheckForNull List<CustomProfileSettings> customProfiles) {
        this.customProfiles = sanitizeCustomProfiles(customProfiles);
    }

    public RuntimeSettings getProfileSettings(String profileName) {
        String normalized = normalize(profileName, null);
        if (normalized == null) {
            throw new IllegalArgumentException("smartRetry profile name must not be blank");
        }
        if (BuiltInProfiles.isBuiltInProfile(normalized)) {
            RuntimeSettings defaults = BuiltInProfiles.defaultsFor(normalized);
            return new RuntimeSettings(
                    defaults.getProfile(),
                    defaults.getRetryableFailureTypes(),
                    maxRetries,
                    parseBackoff(backoff),
                    initialDelaySeconds);
        }
        for (CustomProfileSettings customProfile : customProfiles()) {
            if (customProfile.matchesName(normalized)) {
                return new RuntimeSettings(
                        customProfile.getName(),
                        customProfile.getRetryableFailureTypeSet(),
                        maxRetries,
                        parseBackoff(backoff),
                        initialDelaySeconds);
            }
        }
        throw new IllegalArgumentException("Unknown smartRetry profile: " + normalized);
    }

    public RuntimeSettings resolveStepSettings(
            @CheckForNull String requestedProfile,
            @CheckForNull Integer maxRetriesOverride,
            @CheckForNull String backoffOverride,
            @CheckForNull Integer initialDelaySecondsOverride) {
        String effectiveProfile = normalize(requestedProfile, getDefaultProfile());
        return BuiltInProfiles.resolve(
                getProfileSettings(effectiveProfile), maxRetriesOverride, backoffOverride, initialDelaySecondsOverride);
    }

    public String getDisabledBuiltInRules() {
        return disabledBuiltInRules;
    }

    public String getSupportedDisabledBuiltInRulesList() {
        return String.join(
                ", ",
                io.jenkins.plugins.smart_retry.model.SmartRetryReferenceCatalog.getSupportedDisabledBuiltInRuleIds());
    }

    public Set<String> getDisabledBuiltInRuleIds() {
        return parseDisabledBuiltInRuleIds(disabledBuiltInRules);
    }

    @DataBoundSetter
    public void setDisabledBuiltInRules(@CheckForNull String disabledBuiltInRules) {
        this.disabledBuiltInRules = formatDisabledBuiltInRuleIds(parseDisabledBuiltInRuleIds(disabledBuiltInRules));
    }

    public List<CustomClassificationRule> getCustomClassificationRules() {
        return new ArrayList<>(customClassificationRules());
    }

    @DataBoundSetter
    public void setCustomClassificationRules(@CheckForNull List<CustomClassificationRule> customClassificationRules) {
        this.customClassificationRules = sanitizeCustomClassificationRules(customClassificationRules);
    }

    @Override
    @POST
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        checkAdministerPermission();
        normalizeRepeatableProperties(json);
        validateCustomProfilesForm(json);
        validateCustomClassificationRulesForm(json);
        req.bindJSON(this, json);
        validateCustomProfiles(customProfiles());
        validateCustomClassificationRules(customClassificationRules());
        save();
        return true;
    }

    @POST
    public ListBoxModel doFillDefaultProfileItems() {
        checkAdministerPermission();
        ListBoxModel items = new ListBoxModel();
        items.add(BuiltInProfiles.PROFILE_CONSERVATIVE, BuiltInProfiles.PROFILE_CONSERVATIVE);
        items.add(BuiltInProfiles.PROFILE_INFRA, BuiltInProfiles.PROFILE_INFRA);
        for (CustomProfileSettings customProfile : customProfiles()) {
            String name = customProfile.getName();
            if (!name.isBlank()) {
                items.add(name, name);
            }
        }
        return items;
    }

    @POST
    public ListBoxModel doFillBackoffItems() {
        checkAdministerPermission();
        ListBoxModel items = new ListBoxModel();
        items.add(BACKOFF_FIXED, BACKOFF_FIXED);
        items.add(BACKOFF_EXPONENTIAL, BACKOFF_EXPONENTIAL);
        return items;
    }

    @POST
    public FormValidation doCheckDefaultProfile(@QueryParameter String value) {
        checkAdministerPermission();
        String normalized = normalize(value, DEFAULT_PROFILE);
        if (BuiltInProfiles.isBuiltInProfile(normalized)) {
            return FormValidation.ok("Using built-in profile '" + normalized + "'.");
        }
        for (CustomProfileSettings customProfile : customProfiles()) {
            if (customProfile.matchesName(normalized)) {
                return FormValidation.ok("Using configured custom profile '" + normalized + "'.");
            }
        }
        return FormValidation.warning(
                "Unknown profile name. Smart Retry will reject Pipeline requests that reference this profile.");
    }

    @POST
    public FormValidation doCheckDisabledBuiltInRules(@QueryParameter String value) {
        checkAdministerPermission();
        if (value == null || value.isBlank()) {
            return FormValidation.ok("No built-in rules are disabled.");
        }

        String[] tokens = value.split("[,\\r\\n]+");
        Set<String> supported = DeterministicFailureClassifier.supportedDisabledBuiltInRuleIds();
        List<String> unknown = new ArrayList<>();
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String id = token.trim();
            if (!supported.contains(id)) {
                unknown.add(id);
            }
        }

        if (unknown.isEmpty()) {
            return FormValidation.ok("Only supported built-in rule ids are listed.");
        }
        return FormValidation.warning("Unknown rule ids will be ignored when saving: " + String.join(", ", unknown));
    }

    @POST
    public FormValidation doCheckCustomClassificationRules(@QueryParameter String value) {
        checkAdministerPermission();
        if (value == null || value.isBlank()) {
            return FormValidation.ok("No custom rules are configured.");
        }
        return FormValidation.ok();
    }

    @POST
    public FormValidation doCheckConsoleContextLines(@QueryParameter String value) {
        checkAdministerPermission();
        return validateNonNegativeInteger("Console context lines", value);
    }

    @POST
    public FormValidation doCheckMaxRetries(@QueryParameter String value) {
        checkAdministerPermission();
        return validateNonNegativeInteger("Max retries", value);
    }

    @POST
    public FormValidation doCheckInitialDelaySeconds(@QueryParameter String value) {
        checkAdministerPermission();
        return validateNonNegativeInteger("Initial delay seconds", value);
    }

    private List<CustomProfileSettings> customProfiles() {
        if (customProfiles == null) {
            customProfiles = new ArrayList<>();
        }
        return customProfiles;
    }

    private List<CustomClassificationRule> customClassificationRules() {
        if (customClassificationRules == null) {
            customClassificationRules = new ArrayList<>();
        }
        return customClassificationRules;
    }

    void normalizeRepeatableProperties(JSONObject json) {
        if (isMissingOrEmptyRepeatable(json, "customProfiles")) {
            json.put("customProfiles", JSONArray.fromObject(List.of()));
        }
        if (isMissingOrEmptyRepeatable(json, "customClassificationRules")) {
            json.put("customClassificationRules", JSONArray.fromObject(List.of()));
        }
    }

    private static boolean isMissingOrEmptyRepeatable(JSONObject json, String fieldName) {
        if (!json.has(fieldName)) {
            return true;
        }
        Object rawValue = json.opt(fieldName);
        if (rawValue == null) {
            return true;
        }
        if (rawValue instanceof JSONArray array) {
            return array.isEmpty();
        }
        if (rawValue instanceof JSONObject object) {
            return object.isEmpty();
        }
        return false;
    }

    static void validateCustomProfilesForm(JSONObject json) throws FormException {
        Object rawProfiles = json.opt("customProfiles");
        if (rawProfiles == null) {
            return;
        }

        List<JSONObject> customProfiles = submittedCustomProfiles(rawProfiles);
        Set<String> seenNames = new LinkedHashSet<>();
        for (JSONObject customProfile : customProfiles) {
            validateSubmittedCustomProfile(customProfile, seenNames);
        }
    }

    static void validateCustomProfiles(List<CustomProfileSettings> customProfiles) throws FormException {
        if (customProfiles == null || customProfiles.isEmpty()) {
            return;
        }
        for (CustomProfileSettings customProfile : customProfiles) {
            if (customProfile == null) {
                continue;
            }
            if (!customProfile.getName().isBlank()
                    && customProfile.getRetryableFailureTypeSet().isEmpty()) {
                throw new FormException(
                        "Custom profile '" + customProfile.getName()
                                + "' must select at least one retryable failure type.",
                        "customProfiles");
            }
        }
    }

    static void validateCustomClassificationRulesForm(JSONObject json) throws FormException {
        Object rawRules = json.opt("customClassificationRules");
        if (rawRules == null) {
            return;
        }

        List<JSONObject> customRules = submittedCustomClassificationRules(rawRules);
        Set<String> seenNames = new LinkedHashSet<>();
        for (JSONObject customRule : customRules) {
            validateSubmittedCustomClassificationRule(customRule, seenNames);
        }
    }

    static void validateCustomClassificationRules(List<CustomClassificationRule> customClassificationRules)
            throws FormException {
        if (customClassificationRules == null || customClassificationRules.isEmpty()) {
            return;
        }
        Set<String> seenNames = new LinkedHashSet<>();
        for (CustomClassificationRule customRule : customClassificationRules) {
            if (customRule == null) {
                continue;
            }
            String normalizedName = CustomClassificationRule.normalizeName(customRule.getName());
            if (normalizedName.isBlank()) {
                throw new FormException("Custom classification rule name is required.", "customClassificationRules");
            }
            if (!seenNames.add(normalizedName)) {
                throw new FormException(
                        "Custom classification rule '" + normalizedName + "' is defined more than once.",
                        "customClassificationRules");
            }
            if (CustomClassificationRule.normalizePattern(customRule.getPattern())
                    .isBlank()) {
                throw new FormException(
                        "Custom classification rule '" + normalizedName + "' must define a regex pattern.",
                        "customClassificationRules");
            }
            if (!CustomClassificationRule.supportedFailureTypes().contains(customRule.getFailureType())) {
                throw new FormException(
                        "Custom classification rule '" + normalizedName + "' uses an unsupported failure type.",
                        "customClassificationRules");
            }
            try {
                customRule.compiledPattern();
            } catch (RuntimeException e) {
                throw new FormException(
                        "Custom classification rule '" + normalizedName + "' has an invalid regex pattern.",
                        "customClassificationRules");
            }
        }
    }

    private static boolean hasConfiguredFailureTypes(Object selections) {
        if (selections instanceof JSONArray array) {
            return !array.isEmpty();
        }
        if (selections instanceof String value) {
            return !value.isBlank();
        }
        return false;
    }

    private static List<JSONObject> submittedCustomProfiles(Object rawProfiles) {
        List<JSONObject> customProfiles = new ArrayList<>();
        if (rawProfiles instanceof JSONArray array) {
            for (Object candidate : array) {
                if (candidate instanceof JSONObject profileJson) {
                    customProfiles.add(profileJson);
                }
            }
        } else if (rawProfiles instanceof JSONObject profileJson) {
            customProfiles.add(profileJson);
        }
        return customProfiles;
    }

    private static List<JSONObject> submittedCustomClassificationRules(Object rawRules) {
        List<JSONObject> customRules = new ArrayList<>();
        if (rawRules instanceof JSONArray array) {
            for (Object candidate : array) {
                if (candidate instanceof JSONObject ruleJson) {
                    customRules.add(ruleJson);
                }
            }
        } else if (rawRules instanceof JSONObject ruleJson) {
            customRules.add(ruleJson);
        }
        return customRules;
    }

    private static void validateSubmittedCustomProfile(JSONObject customProfile, Set<String> seenNames)
            throws FormException {
        String normalizedName = CustomProfileSettings.normalizeName(customProfile.optString("name"));
        if (normalizedName.isBlank()) {
            throw new FormException("Custom profile name is required.", "customProfiles");
        }
        if (BuiltInProfiles.isBuiltInProfile(normalizedName)) {
            throw new FormException(
                    "Custom profile '" + normalizedName + "' uses a reserved built-in profile name.", "customProfiles");
        }
        if (!seenNames.add(normalizedName)) {
            throw new FormException(
                    "Custom profile '" + normalizedName + "' is defined more than once.", "customProfiles");
        }
        if (!hasConfiguredFailureTypes(customProfile.opt("retryableFailureTypeSelections"))) {
            throw new FormException(
                    "Custom profile '" + normalizedName + "' must select at least one retryable failure type.",
                    "customProfiles");
        }
    }

    private static void validateSubmittedCustomClassificationRule(JSONObject customRule, Set<String> seenNames)
            throws FormException {
        String normalizedName = CustomClassificationRule.normalizeName(customRule.optString("nameSuffix"));
        if (normalizedName.isBlank()) {
            throw new FormException("Custom classification rule name is required.", "customClassificationRules");
        }
        if (!seenNames.add(normalizedName)) {
            throw new FormException(
                    "Custom classification rule '" + normalizedName + "' is defined more than once.",
                    "customClassificationRules");
        }

        String pattern = CustomClassificationRule.normalizePattern(customRule.optString("pattern"));
        if (pattern.isBlank()) {
            throw new FormException(
                    "Custom classification rule '" + normalizedName + "' must define a regex pattern.",
                    "customClassificationRules");
        }
        try {
            java.util.regex.Pattern.compile(pattern);
        } catch (RuntimeException e) {
            throw new FormException(
                    "Custom classification rule '" + normalizedName + "' has an invalid regex pattern.",
                    "customClassificationRules");
        }

        String rawFailureType = customRule.optString("failureType");
        if (rawFailureType == null || rawFailureType.isBlank()) {
            throw new FormException(
                    "Custom classification rule '" + normalizedName + "' must select a failure type.",
                    "customClassificationRules");
        }
        try {
            io.jenkins.plugins.smart_retry.model.FailureType failureType =
                    io.jenkins.plugins.smart_retry.model.FailureType.valueOf(
                            rawFailureType.trim().toUpperCase(Locale.ROOT));
            if (!CustomClassificationRule.supportedFailureTypes().contains(failureType)) {
                throw new FormException(
                        "Custom classification rule '" + normalizedName + "' uses an unsupported failure type.",
                        "customClassificationRules");
            }
        } catch (IllegalArgumentException e) {
            throw new FormException(
                    "Custom classification rule '" + normalizedName + "' uses an unsupported failure type.",
                    "customClassificationRules");
        }
    }

    private static List<CustomProfileSettings> sanitizeCustomProfiles(
            @CheckForNull List<CustomProfileSettings> rawProfiles) {
        if (rawProfiles == null || rawProfiles.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, CustomProfileSettings> orderedProfiles = new LinkedHashMap<>();
        for (CustomProfileSettings rawProfile : rawProfiles) {
            if (rawProfile == null) {
                continue;
            }
            String normalizedName = CustomProfileSettings.normalizeName(rawProfile.getName());
            if (normalizedName.isBlank() || BuiltInProfiles.isBuiltInProfile(normalizedName)) {
                continue;
            }
            if (orderedProfiles.containsKey(normalizedName)) {
                continue;
            }
            CustomProfileSettings sanitized = new CustomProfileSettings();
            sanitized.setName(normalizedName);
            sanitized.setRetryableFailureTypes(rawProfile.getRetryableFailureTypes());
            orderedProfiles.put(normalizedName, sanitized);
        }
        return new ArrayList<>(orderedProfiles.values());
    }

    private static List<CustomClassificationRule> sanitizeCustomClassificationRules(
            @CheckForNull List<CustomClassificationRule> rawRules) {
        if (rawRules == null || rawRules.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, CustomClassificationRule> orderedRules = new LinkedHashMap<>();
        for (CustomClassificationRule rawRule : rawRules) {
            if (rawRule == null) {
                continue;
            }
            String normalizedName = CustomClassificationRule.normalizeName(rawRule.getName());
            String normalizedPattern = CustomClassificationRule.normalizePattern(rawRule.getPattern());
            if (normalizedName.isBlank() || normalizedPattern.isBlank()) {
                continue;
            }
            if (!CustomClassificationRule.supportedFailureTypes().contains(rawRule.getFailureType())) {
                continue;
            }
            if (orderedRules.containsKey(normalizedName)) {
                continue;
            }

            CustomClassificationRule sanitized = new CustomClassificationRule();
            sanitized.setName(normalizedName);
            sanitized.setPattern(normalizedPattern);
            sanitized.setFailureType(rawRule.getFailureType());
            sanitized.setEnabled(rawRule.isEnabled());
            sanitized.setDescription(rawRule.getDescription());
            orderedRules.put(normalizedName, sanitized);
        }
        return new ArrayList<>(orderedRules.values());
    }

    private static String normalize(@CheckForNull String value, @CheckForNull String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static FormValidation validateNonNegativeInteger(String label, @CheckForNull String value) {
        if (value == null || value.isBlank()) {
            return FormValidation.error(label + " is required.");
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 0) {
                return FormValidation.error(label + " must be 0 or greater.");
            }
            return FormValidation.ok(label + " is valid.");
        } catch (NumberFormatException ignored) {
            return FormValidation.error(label + " must be a whole number.");
        }
    }

    private static String normalizeBackoff(@CheckForNull String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!Objects.equals(normalized, BACKOFF_FIXED) && !Objects.equals(normalized, BACKOFF_EXPONENTIAL)) {
            return fallback;
        }
        return normalized;
    }

    private static BackoffStrategy parseBackoff(String rawValue) {
        return BACKOFF_EXPONENTIAL.equals(normalizeBackoff(rawValue, BACKOFF_FIXED))
                ? BackoffStrategy.EXPONENTIAL
                : BackoffStrategy.FIXED;
    }

    private static Set<String> parseDisabledBuiltInRuleIds(@CheckForNull String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        String[] tokens = rawValue.split("[,\\r\\n]+");
        Set<String> supported = DeterministicFailureClassifier.supportedDisabledBuiltInRuleIds();
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String id = token.trim();
            if (supported.contains(id)) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(ids));
    }

    private static String formatDisabledBuiltInRuleIds(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        return String.join("\n", ids);
    }

    private static String defaultBackoffName() {
        return BACKOFF_FIXED;
    }
}

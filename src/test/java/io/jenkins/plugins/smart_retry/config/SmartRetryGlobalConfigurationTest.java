package io.jenkins.plugins.smart_retry.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.smart_retry.model.FailureType;
import io.jenkins.plugins.smart_retry.model.SmartRetryReferenceCatalog;
import io.jenkins.plugins.smart_retry.policy.BackoffStrategy;
import io.jenkins.plugins.smart_retry.policy.BuiltInProfiles;
import java.util.List;
import java.util.Set;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.html.HtmlButton;
import org.htmlunit.html.HtmlElement;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.verb.POST;

@WithJenkins
class SmartRetryGlobalConfigurationTest {

    @Test
    void persistsAndReloads(JenkinsRule jenkins) throws Exception {
        SmartRetryGlobalConfiguration cfg = SmartRetryGlobalConfiguration.get();

        CustomProfileSettings release = new CustomProfileSettings();
        release.setName("release");
        release.setRetryableFailureTypes("network_transient\nartifact_repo_transient\nunknown");

        CustomProfileSettings duplicate = new CustomProfileSettings();
        duplicate.setName("release");
        duplicate.setRetryableFailureTypes("agent_lost");

        CustomProfileSettings reserved = new CustomProfileSettings();
        reserved.setName(BuiltInProfiles.PROFILE_INFRA);
        reserved.setRetryableFailureTypes("agent_lost");

        CustomClassificationRule resetRule = new CustomClassificationRule();
        resetRule.setNameSuffix("network-reset");
        resetRule.setPattern("connection reset");
        resetRule.setFailureType(FailureType.NETWORK_TRANSIENT);
        resetRule.setDescription("Network reset");

        CustomClassificationRule duplicateRule = new CustomClassificationRule();
        duplicateRule.setNameSuffix("network-reset");
        duplicateRule.setPattern("broken pipe");
        duplicateRule.setFailureType(FailureType.NETWORK_TRANSIENT);

        cfg.setDefaultProfile("release");
        cfg.setConsoleContextLines(50);
        cfg.setMaxRetries(3);
        cfg.setBackoff(BuiltInProfiles.BACKOFF_EXPONENTIAL);
        cfg.setInitialDelaySeconds(7);
        cfg.setCustomProfiles(List.of(release, duplicate, reserved));
        cfg.setCustomClassificationRules(List.of(resetRule, duplicateRule));
        cfg.setDisabledBuiltInRules("scm-remote-end-hung-up\nflow-interrupted\nartifact-connection-reset\nnot-a-rule");
        cfg.save();

        cfg.load();

        assertEquals("release", cfg.getDefaultProfile());
        assertEquals(50, cfg.getConsoleContextLines());
        assertEquals(3, cfg.getMaxRetries());
        assertEquals(BuiltInProfiles.BACKOFF_EXPONENTIAL, cfg.getBackoff());
        assertEquals(7, cfg.getInitialDelaySeconds());
        assertEquals(1, cfg.getCustomProfiles().size());
        assertEquals("release", cfg.getCustomProfiles().get(0).getName());
        assertEquals(
                "NETWORK_TRANSIENT\nARTIFACT_REPO_TRANSIENT",
                cfg.getCustomProfiles().get(0).getRetryableFailureTypes());
        assertEquals(
                Set.of(FailureType.AGENT_LOST, FailureType.SCM_TRANSIENT),
                cfg.getProfileSettings(BuiltInProfiles.PROFILE_CONSERVATIVE).getRetryableFailureTypes());
        assertEquals(
                BackoffStrategy.EXPONENTIAL,
                cfg.getProfileSettings(BuiltInProfiles.PROFILE_CONSERVATIVE).getBackoff());
        assertEquals(
                Set.of(
                        FailureType.AGENT_LOST,
                        FailureType.SCM_TRANSIENT,
                        FailureType.NETWORK_TRANSIENT,
                        FailureType.ARTIFACT_REPO_TRANSIENT,
                        FailureType.IDENTITY_PROVIDER_TRANSIENT),
                cfg.getProfileSettings(BuiltInProfiles.PROFILE_INFRA).getRetryableFailureTypes());
        assertEquals(3, cfg.getProfileSettings(BuiltInProfiles.PROFILE_INFRA).getMaxRetries());
        assertEquals(
                Set.of(FailureType.NETWORK_TRANSIENT, FailureType.ARTIFACT_REPO_TRANSIENT),
                cfg.getProfileSettings("release").getRetryableFailureTypes());
        assertEquals(
                BackoffStrategy.EXPONENTIAL, cfg.getProfileSettings("release").getBackoff());
        assertEquals(1, cfg.getCustomClassificationRules().size());
        assertEquals(
                "custom-rule-network-reset",
                cfg.getCustomClassificationRules().get(0).getName());
        assertEquals("network-reset", cfg.getCustomClassificationRules().get(0).getNameSuffix());
        assertEquals(
                "connection reset", cfg.getCustomClassificationRules().get(0).getPattern());
        assertEquals("scm-remote-end-hung-up\nartifact-connection-reset", cfg.getDisabledBuiltInRules());
        assertEquals(Set.of("scm-remote-end-hung-up", "artifact-connection-reset"), cfg.getDisabledBuiltInRuleIds());
    }

    @Test
    void rejectsUnknownProfilesAtResolutionTime(JenkinsRule jenkins) {
        SmartRetryGlobalConfiguration cfg = SmartRetryGlobalConfiguration.get();

        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> cfg.getProfileSettings("missing"));

        assertEquals("Unknown smartRetry profile: missing", exception.getMessage());
    }

    @Test
    void exposesProfileAndBackoffChoices(JenkinsRule jenkins) {
        SmartRetryGlobalConfiguration cfg = SmartRetryGlobalConfiguration.get();

        CustomProfileSettings release = new CustomProfileSettings();
        release.setName("release");
        release.setRetryableFailureTypes("network_transient");
        cfg.setCustomProfiles(List.of(release));

        ListBoxModel profileItems = cfg.doFillDefaultProfileItems();
        ListBoxModel backoffItems = cfg.doFillBackoffItems();

        assertEquals(3, profileItems.size());
        assertEquals(BuiltInProfiles.PROFILE_CONSERVATIVE, profileItems.get(0).value);
        assertEquals(BuiltInProfiles.PROFILE_INFRA, profileItems.get(1).value);
        assertEquals("release", profileItems.get(2).value);
        assertEquals(2, backoffItems.size());
        assertEquals(BuiltInProfiles.BACKOFF_FIXED, backoffItems.get(0).value);
        assertEquals(BuiltInProfiles.BACKOFF_EXPONENTIAL, backoffItems.get(1).value);
    }

    @Test
    void validatesDefaultProfileAndDisabledRules(JenkinsRule jenkins) {
        SmartRetryGlobalConfiguration cfg = SmartRetryGlobalConfiguration.get();

        CustomProfileSettings release = new CustomProfileSettings();
        release.setName("release");
        release.setRetryableFailureTypes("network_transient");
        cfg.setCustomProfiles(List.of(release));

        assertEquals(FormValidation.Kind.OK, cfg.doCheckDefaultProfile(BuiltInProfiles.PROFILE_INFRA).kind);
        assertEquals(FormValidation.Kind.OK, cfg.doCheckDefaultProfile("release").kind);
        assertEquals(FormValidation.Kind.WARNING, cfg.doCheckDefaultProfile("missing").kind);
        assertEquals(FormValidation.Kind.OK, cfg.doCheckConsoleContextLines("200").kind);
        assertEquals(FormValidation.Kind.ERROR, cfg.doCheckConsoleContextLines("-1").kind);
        assertEquals(FormValidation.Kind.OK, cfg.doCheckMaxRetries("3").kind);
        assertEquals(FormValidation.Kind.ERROR, cfg.doCheckMaxRetries("abc").kind);
        assertEquals(FormValidation.Kind.OK, cfg.doCheckInitialDelaySeconds("7").kind);
        assertEquals(FormValidation.Kind.ERROR, cfg.doCheckInitialDelaySeconds("").kind);
        assertEquals(FormValidation.Kind.OK, cfg.doCheckDisabledBuiltInRules("").kind);
        assertEquals(
                FormValidation.Kind.OK,
                cfg.doCheckDisabledBuiltInRules("scm-remote-end-hung-up\nartifact-connection-reset").kind);
        assertEquals(
                FormValidation.Kind.WARNING,
                cfg.doCheckDisabledBuiltInRules("scm-remote-end-hung-up\nunknown-rule").kind);
    }

    @Test
    void rejectsCustomClassificationRulesWithoutPattern() {
        CustomClassificationRule rule = new CustomClassificationRule();
        rule.setNameSuffix("missing-pattern");
        rule.setFailureType(FailureType.NETWORK_TRANSIENT);

        Descriptor.FormException exception = assertThrows(
                Descriptor.FormException.class,
                () -> SmartRetryGlobalConfiguration.validateCustomClassificationRules(List.of(rule)));

        assertEquals(
                "Custom classification rule 'custom-rule-missing-pattern' must define a regex pattern.",
                exception.getMessage());
    }

    @Test
    void rejectsInvalidCustomClassificationRulesInSubmittedForm() {
        JSONObject duplicate = new JSONObject();
        duplicate.put("nameSuffix", "network-reset");
        duplicate.put("pattern", "connection reset");
        duplicate.put("failureType", "NETWORK_TRANSIENT");

        JSONObject duplicateAgain = new JSONObject();
        duplicateAgain.put("nameSuffix", "network-reset");
        duplicateAgain.put("pattern", "broken pipe");
        duplicateAgain.put("failureType", "NETWORK_TRANSIENT");

        JSONObject blank = new JSONObject();
        blank.put("nameSuffix", "");
        blank.put("pattern", "connection reset");
        blank.put("failureType", "NETWORK_TRANSIENT");

        JSONObject missingFailureType = new JSONObject();
        missingFailureType.put("nameSuffix", "missing-type");
        missingFailureType.put("pattern", "connection reset");

        JSONObject unsupportedFailureType = new JSONObject();
        unsupportedFailureType.put("nameSuffix", "unsupported-type");
        unsupportedFailureType.put("pattern", "connection reset");
        unsupportedFailureType.put("failureType", "UNKNOWN");

        JSONObject duplicateForm = new JSONObject();
        duplicateForm.put("customClassificationRules", JSONArray.fromObject(List.of(duplicate, duplicateAgain)));

        Descriptor.FormException duplicateException = assertThrows(
                Descriptor.FormException.class,
                () -> SmartRetryGlobalConfiguration.validateCustomClassificationRulesForm(duplicateForm));
        assertEquals(
                "Custom classification rule 'custom-rule-network-reset' is defined more than once.",
                duplicateException.getMessage());

        JSONObject blankForm = new JSONObject();
        blankForm.put("customClassificationRules", blank);

        Descriptor.FormException blankException = assertThrows(
                Descriptor.FormException.class,
                () -> SmartRetryGlobalConfiguration.validateCustomClassificationRulesForm(blankForm));
        assertEquals("Custom classification rule name is required.", blankException.getMessage());

        JSONObject missingFailureTypeForm = new JSONObject();
        missingFailureTypeForm.put("customClassificationRules", missingFailureType);

        Descriptor.FormException missingFailureTypeException = assertThrows(
                Descriptor.FormException.class,
                () -> SmartRetryGlobalConfiguration.validateCustomClassificationRulesForm(missingFailureTypeForm));
        assertEquals(
                "Custom classification rule 'custom-rule-missing-type' must select a failure type.",
                missingFailureTypeException.getMessage());

        JSONObject unsupportedFailureTypeForm = new JSONObject();
        unsupportedFailureTypeForm.put("customClassificationRules", unsupportedFailureType);

        Descriptor.FormException unsupportedFailureTypeException = assertThrows(
                Descriptor.FormException.class,
                () -> SmartRetryGlobalConfiguration.validateCustomClassificationRulesForm(unsupportedFailureTypeForm));
        assertEquals(
                "Custom classification rule 'custom-rule-unsupported-type' uses an unsupported failure type.",
                unsupportedFailureTypeException.getMessage());
    }

    @Test
    void configureRequiresPost() throws Exception {
        assertNotNull(SmartRetryGlobalConfiguration.class
                .getMethod("configure", org.kohsuke.stapler.StaplerRequest2.class, JSONObject.class)
                .getAnnotation(POST.class));
    }

    @Test
    void rejectsCustomProfilesWithoutRetryableFailureTypes() {
        CustomProfileSettings empty = new CustomProfileSettings();
        empty.setName("empty");

        Descriptor.FormException exception = assertThrows(
                Descriptor.FormException.class,
                () -> SmartRetryGlobalConfiguration.validateCustomProfiles(List.of(empty)));

        assertEquals("Custom profile 'empty' must select at least one retryable failure type.", exception.getMessage());
    }

    @Test
    void rejectsInvalidCustomProfileNamesInSubmittedForm() {
        JSONObject duplicate = new JSONObject();
        duplicate.put("name", "release");
        duplicate.put("retryableFailureTypeSelections", JSONArray.fromObject(List.of("NETWORK_TRANSIENT")));

        JSONObject duplicateAgain = new JSONObject();
        duplicateAgain.put("name", "release");
        duplicateAgain.put("retryableFailureTypeSelections", JSONArray.fromObject(List.of("AGENT_LOST")));

        JSONObject reserved = new JSONObject();
        reserved.put("name", BuiltInProfiles.PROFILE_INFRA);
        reserved.put("retryableFailureTypeSelections", JSONArray.fromObject(List.of("AGENT_LOST")));

        JSONObject blank = new JSONObject();
        blank.put("name", "");
        blank.put("retryableFailureTypeSelections", JSONArray.fromObject(List.of("AGENT_LOST")));

        JSONObject duplicateForm = new JSONObject();
        duplicateForm.put("customProfiles", JSONArray.fromObject(List.of(duplicate, duplicateAgain)));

        Descriptor.FormException duplicateException = assertThrows(
                Descriptor.FormException.class,
                () -> SmartRetryGlobalConfiguration.validateCustomProfilesForm(duplicateForm));
        assertEquals("Custom profile 'release' is defined more than once.", duplicateException.getMessage());

        JSONObject reservedForm = new JSONObject();
        reservedForm.put("customProfiles", reserved);

        Descriptor.FormException reservedException = assertThrows(
                Descriptor.FormException.class,
                () -> SmartRetryGlobalConfiguration.validateCustomProfilesForm(reservedForm));
        assertEquals(
                "Custom profile '" + BuiltInProfiles.PROFILE_INFRA + "' uses a reserved built-in profile name.",
                reservedException.getMessage());

        JSONObject blankForm = new JSONObject();
        blankForm.put("customProfiles", blank);

        Descriptor.FormException blankException = assertThrows(
                Descriptor.FormException.class,
                () -> SmartRetryGlobalConfiguration.validateCustomProfilesForm(blankForm));
        assertEquals("Custom profile name is required.", blankException.getMessage());
    }

    @Test
    void clearsCustomClassificationRulesWhenResetToEmptyList(JenkinsRule jenkins) {
        SmartRetryGlobalConfiguration cfg = SmartRetryGlobalConfiguration.get();

        CustomClassificationRule rule = new CustomClassificationRule();
        rule.setNameSuffix("network-reset");
        rule.setPattern("connection reset");
        rule.setFailureType(FailureType.NETWORK_TRANSIENT);

        cfg.setCustomClassificationRules(List.of(rule));
        cfg.setCustomClassificationRules(List.of());

        assertEquals(0, cfg.getCustomClassificationRules().size());
    }

    @Test
    void normalizesMissingRepeatablePropertiesInSubmittedJson(JenkinsRule jenkins) {
        SmartRetryGlobalConfiguration cfg = SmartRetryGlobalConfiguration.get();

        JSONObject json = new JSONObject();
        json.put("customProfiles", JSONArray.fromObject(List.of()));
        json.put("customClassificationRules", new JSONObject());

        cfg.normalizeRepeatableProperties(json);

        assertEquals(2, json.keySet().size());
        assertTrue(json.get("customProfiles") instanceof JSONArray);
        assertTrue(((JSONArray) json.get("customProfiles")).isEmpty());
        assertTrue(json.get("customClassificationRules") instanceof JSONArray);
        assertTrue(((JSONArray) json.get("customClassificationRules")).isEmpty());
    }

    @Test
    void normalizesEmptyRepeatablesBeforeValidation(JenkinsRule jenkins) throws Exception {
        SmartRetryGlobalConfiguration cfg = SmartRetryGlobalConfiguration.get();

        JSONObject json = new JSONObject();
        json.put("customProfiles", new JSONObject());
        json.put("customClassificationRules", new JSONObject());

        cfg.normalizeRepeatableProperties(json);

        SmartRetryGlobalConfiguration.validateCustomProfilesForm(json);
        SmartRetryGlobalConfiguration.validateCustomClassificationRulesForm(json);

        assertTrue(((JSONArray) json.get("customProfiles")).isEmpty());
        assertTrue(((JSONArray) json.get("customClassificationRules")).isEmpty());
    }

    @Test
    void deletesLastCustomClassificationRuleFromConfigurePage(JenkinsRule jenkins) throws Exception {
        SmartRetryGlobalConfiguration cfg = SmartRetryGlobalConfiguration.get();

        CustomClassificationRule rule = new CustomClassificationRule();
        rule.setNameSuffix("network-reset");
        rule.setPattern("connection reset");
        rule.setFailureType(FailureType.NETWORK_TRANSIENT);
        cfg.setCustomClassificationRules(List.of(rule));
        cfg.save();

        JenkinsRule.WebClient webClient = jenkins.createWebClient();
        HtmlPage page = webClient.goTo("manage/configure");
        HtmlForm form = page.getFormByName("config");

        List<?> chunks = page.getByXPath(
                "//div[contains(@class,'repeated-chunk') and @name='customClassificationRules' and not(contains(@class,'to-be-removed'))]");
        assertEquals(1, chunks.size());

        HtmlButton deleteButton = page.getFirstByXPath(
                "(//div[contains(@class,'repeated-chunk') and @name='customClassificationRules' and not(contains(@class,'to-be-removed'))]//button[contains(@class,'repeatable-delete')])[1]");
        deleteButton.click();
        webClient.waitForBackgroundJavaScript(1000);

        jenkins.submit(form);

        assertTrue(cfg.getCustomClassificationRules().isEmpty());
    }

    @Test
    void deletesLastCustomProfileFromConfigurePage(JenkinsRule jenkins) throws Exception {
        SmartRetryGlobalConfiguration cfg = SmartRetryGlobalConfiguration.get();

        CustomProfileSettings profile = new CustomProfileSettings();
        profile.setName("release");
        profile.setRetryableFailureTypes("network_transient");
        cfg.setCustomProfiles(List.of(profile));
        cfg.save();

        JenkinsRule.WebClient webClient = jenkins.createWebClient();
        HtmlPage page = webClient.goTo("manage/configure");
        HtmlForm form = page.getFormByName("config");

        List<?> chunks = page.getByXPath(
                "//div[contains(@class,'repeated-chunk') and @name='customProfiles' and not(contains(@class,'to-be-removed'))]");
        assertEquals(1, chunks.size());

        HtmlButton deleteButton = page.getFirstByXPath(
                "(//div[contains(@class,'repeated-chunk') and @name='customProfiles' and not(contains(@class,'to-be-removed'))]//button[contains(@class,'repeatable-delete')])[1]");
        deleteButton.click();
        webClient.waitForBackgroundJavaScript(1000);

        jenkins.submit(form);

        assertTrue(cfg.getCustomProfiles().isEmpty());
    }

    @Test
    void supportedDisabledBuiltInRulesListMatchesReferenceCatalog(JenkinsRule jenkins) {
        SmartRetryGlobalConfiguration cfg = SmartRetryGlobalConfiguration.get();

        String expected = String.join(", ", SmartRetryReferenceCatalog.getSupportedDisabledBuiltInRuleIds());

        assertEquals(expected, cfg.getSupportedDisabledBuiltInRulesList());
    }

    @Test
    void globalConfigurationDisplaysSupportedDisabledBuiltInRuleIds(JenkinsRule jenkins) throws Exception {
        SmartRetryGlobalConfiguration cfg = SmartRetryGlobalConfiguration.get();
        String expectedDescription = "Supported IDs: " + cfg.getSupportedDisabledBuiltInRulesList();

        JenkinsRule.WebClient webClient = jenkins.createWebClient();
        HtmlPage page = webClient.goTo("manage/configure");

        List<?> descriptions = page.getByXPath("//*[contains(concat(' ', normalize-space(@class), ' '), "
                + "' jenkins-form-description ') "
                + "and contains(normalize-space(.), 'Supported IDs:')]");

        assertEquals(1, descriptions.size());

        HtmlElement description = (HtmlElement) descriptions.get(0);
        String actualDescription = description.getTextContent().trim().replaceAll("\\s+", " ");

        assertEquals(expectedDescription, actualDescription);
    }
}

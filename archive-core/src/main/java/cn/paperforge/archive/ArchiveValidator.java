package cn.paperforge.archive;

import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.results.TestAssertion;
import org.verapdf.processor.reports.enums.JobEndStatus;
import org.verapdf.pdfa.validation.profiles.RuleId;

/** Complete upstream profiles evaluated locally in the optional phone engine. */
public final class ArchiveValidator {
    public static final String VERSION = "verapdf-1.30.3-android1";

    private static JSONObject id(RuleId id) throws Exception {
        return new JSONObject().put("specification", id.getSpecification().toString())
            .put("clause", id.getClause()).put("test", id.getTestNumber());
    }

    private static String digest(InputStream input) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        byte[] bytes = new byte[65536];
        for (int count; (count = input.read(bytes)) >= 0;) hash.update(bytes, 0, count);
        StringBuilder result = new StringBuilder();
        for (byte value : hash.digest()) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    public static JSONObject validate(File file, String target) throws Exception {
        PDFAFlavour flavour = PDFAFlavour.byFlavourId(target);
        if (flavour == null || !target.matches("[123][abu]|4[ef]?"))
            throw new IllegalArgumentException("请选择 PDF/A 目标等级");
        VeraGreenfieldFoundryProvider.initialise();
        var profile = AndroidProfiles.load(flavour);
        var foundry = Foundries.defaultInstance();
        String sourceHash;
        try (InputStream in = new java.io.FileInputStream(file)) { sourceHash = digest(in); }
        String profileHash;
        String resource = "/org/verapdf/pdfa/validation/" + flavour.name().replace("PDFA_", "PDFA-").replace("_", "") + ".xml";
        try (InputStream in = ArchiveValidator.class.getResourceAsStream(resource)) { profileHash = digest(in); }
        try (var parser = foundry.createParser(file, flavour); var validator = foundry.createValidator(profile, false)) {
            var result = validator.validate(parser);
            JSONArray rules = new JSONArray();
            for (var entry : result.getFailedChecks().entrySet()) {
                var rule = profile.getRuleByRuleId(entry.getKey());
                rules.put(id(entry.getKey()).put("count", entry.getValue())
                    .put("description", rule == null ? "" : rule.getDescription())
                    .put("object", rule == null ? "" : rule.getObject()));
            }
            JSONArray failures = new JSONArray();
            for (var assertion : result.getTestAssertions()) {
                if (assertion.getStatus() == TestAssertion.Status.PASSED || failures.length() >= 500) continue;
                String context = assertion.getLocationContext();
                JSONObject detail = id(assertion.getRuleId()).put("status", assertion.getStatus().name())
                    .put("message", assertion.getMessage()).put("error", assertion.getErrorMessage())
                    .put("context", context == null ? "" : context);
                java.util.regex.Matcher page = java.util.regex.Pattern.compile("pages\\[(\\d+)\\]").matcher(context == null ? "" : context);
                if (page.find()) detail.put("page", Integer.parseInt(page.group(1)) + 1);
                failures.put(detail);
            }
            return new JSONObject().put("schema", 1).put("kind", "paperforge-pdfa-report")
                .put("engine", VERSION).put("target", target).put("sourceSha256", sourceHash)
                .put("profileSha256", profileHash).put("profileRuleCount", profile.getRules().size())
                .put("profileVariableCount", profile.getVariables().size())
                .put("checkedAt", System.currentTimeMillis()).put("assertions", result.getTotalAssertions())
                .put("jobEndStatus", result.getJobEndStatus().name())
                .put("compliant", result.isCompliant() && result.getJobEndStatus() == JobEndStatus.NORMAL)
                .put("failedRules", rules).put("failures", failures)
                .put("displayedFailuresLimitedTo", 500).put("offline", true);
        }
    }
}

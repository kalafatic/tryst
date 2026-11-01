package eu.kalafatic.tryst.domain.review;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Domain service providing automated code inspection on diffs, modified files, and test results.
 * Distinguishes confirmed defects from suggestions and ensures safety.
 */
public class CodeReviewService {

    public enum FindingSeverity {
        ERROR,
        WARNING,
        SUGGESTION
    }

    public record ReviewFinding(
            String file,
            int line,
            FindingSeverity severity,
            String category,
            String message
    ) {}

    public record ReviewReport(
            boolean approved,
            int defectCount,
            int warningCount,
            List<ReviewFinding> findings,
            String summary
    ) {}

    public ReviewReport reviewChanges(String gitDiff, String testOutput, boolean buildSucceeded) {
        List<ReviewFinding> findings = new ArrayList<>();

        if (!buildSucceeded) {
            findings.add(new ReviewFinding(
                    "build", 0, FindingSeverity.ERROR, "Build Failure",
                    "The build failed during the autonomous execution cycle. Fix compilation or packaging errors."
            ));
        }

        if (testOutput != null && testOutput.contains("FAILURE") || (testOutput != null && testOutput.contains("Failures: [1-9]"))) {
            findings.add(new ReviewFinding(
                    "tests", 0, FindingSeverity.ERROR, "Test Failure",
                    "Automated tests reported failures. Ensure all unit and regression tests pass."
            ));
        }

        if (gitDiff != null) {
            inspectDiff(gitDiff, findings);
        }

        int defects = (int) findings.stream().filter(f -> f.severity() == FindingSeverity.ERROR).count();
        int warnings = (int) findings.stream().filter(f -> f.severity() == FindingSeverity.WARNING).count();
        boolean approved = defects == 0;
        String summary = approved
                ? "Code review passed with " + warnings + " suggestions/warnings."
                : "Code review found " + defects + " blocking defects that need correction.";

        return new ReviewReport(approved, defects, warnings, Collections.unmodifiableList(findings), summary);
    }

    private void inspectDiff(String diff, List<ReviewFinding> findings) {
        String currentFile = "unknown";
        int currentLine = 0;

        for (String line : diff.split("\n")) {
            if (line.startsWith("+++ b/")) {
                currentFile = line.substring("+++ b/".length()).trim();
            } else if (line.startsWith("@@")) {
                currentLine = 1;
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                currentLine++;
                String addedCode = line.substring(1).trim();

                // Check for obvious hardcoded secrets or risky patterns
                if (addedCode.contains("password =") || addedCode.contains("SECRET_KEY") || addedCode.contains("API_KEY = \"")) {
                    findings.add(new ReviewFinding(
                            currentFile, currentLine, FindingSeverity.ERROR, "Security Risk",
                            "Possible hardcoded secret detected: " + addedCode
                    ));
                }

                // Check for System.exit
                if (addedCode.contains("System.exit(")) {
                    findings.add(new ReviewFinding(
                            currentFile, currentLine, FindingSeverity.ERROR, "Execution Safety",
                            "System.exit() must not be called inside Eclipse RCP application plugins."
                    ));
                }

                // Check for empty catch blocks
                if (addedCode.contains("catch (Exception e) {}") || addedCode.contains("catch (Throwable t) {}")) {
                    findings.add(new ReviewFinding(
                            currentFile, currentLine, FindingSeverity.WARNING, "Error Handling",
                            "Empty catch block detected. Exceptions should be logged or handled cleanly."
                    ));
                }
            }
        }
    }
}

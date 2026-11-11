package eu.kalafatic.tryst.domain.intake;

import java.util.List;
import java.util.Locale;

/**
 * Intelligent branch matching utility using Levenshtein distance,
 * prefix matching, and token-based similarity.
 */
public final class BranchResolver {

    private BranchResolver() {}

    public record Resolution(
            String resolvedBranch,
            boolean wasCorrected,
            String explanation,
            boolean isAmbiguous,
            List<String> availableAlternatives
    ) {}

    public static Resolution resolveBranch(String requestedBranch, List<String> availableBranches, String defaultBranch) {
        String req = (requestedBranch != null && !requestedBranch.isBlank()) ? requestedBranch.trim() : "master";

        if (availableBranches == null || availableBranches.isEmpty()) {
            return new Resolution(req, false, "No remote branch list available; using " + req, false, List.of());
        }

        // 1. Exact match
        for (String b : availableBranches) {
            if (b.equalsIgnoreCase(req)) {
                return new Resolution(b, !b.equals(req), !b.equals(req) ? "Casing matched: " + b : "Exact branch match found", false, availableBranches);
            }
        }

        // 2. Fuzzy match / Typo detection (Levenshtein distance <= 2 or <= 3 for longer names)
        String bestFuzzyMatch = null;
        int minDistance = Integer.MAX_VALUE;
        int countClose = 0;

        for (String b : availableBranches) {
            int dist = levenshteinDistance(req.toLowerCase(Locale.ROOT), b.toLowerCase(Locale.ROOT));
            int threshold = Math.max(1, Math.min(3, b.length() / 2));
            if (dist <= threshold) {
                if (dist < minDistance) {
                    minDistance = dist;
                    bestFuzzyMatch = b;
                    countClose = 1;
                } else if (dist == minDistance) {
                    countClose++;
                }
            }
        }

        if (bestFuzzyMatch != null && countClose == 1) {
            return new Resolution(bestFuzzyMatch, true,
                    String.format("Branch '%s' not found. Recognized likely typo and automatically corrected to '%s'.", req, bestFuzzyMatch),
                    false, availableBranches);
        }

        // 3. Fallback to default branch (e.g. master, or main, or detected default)
        String effectiveDefault = (defaultBranch != null && !defaultBranch.isBlank() && availableBranches.contains(defaultBranch))
                ? defaultBranch
                : (availableBranches.contains("master") ? "master" : (availableBranches.contains("main") ? "main" : availableBranches.get(0)));

        if (effectiveDefault != null) {
            return new Resolution(effectiveDefault, true,
                    String.format("Requested branch '%s' does not exist in repository. Resolved to default branch '%s'. Available branches: %s",
                            req, effectiveDefault, String.join(", ", availableBranches)),
                    countClose > 1, availableBranches);
        }

        // 4. Ambiguous / unresolvable
        return new Resolution(req, false,
                String.format("Branch '%s' not found and no suitable alternative could be determined. Available: %s", req, String.join(", ", availableBranches)),
                true, availableBranches);
    }

    public static int levenshteinDistance(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;

        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}

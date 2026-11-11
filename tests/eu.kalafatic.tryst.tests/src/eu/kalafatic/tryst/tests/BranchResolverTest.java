package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.intake.BranchResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BranchResolverTest {

    @Test
    void testExactMatch() {
        var res = BranchResolver.resolveBranch("master", List.of("master", "develop", "feature/new"), "master");
        assertEquals("master", res.resolvedBranch());
        assertFalse(res.wasCorrected());
    }

    @Test
    void testTypoDetection() {
        // "mastr" -> "master"
        var res = BranchResolver.resolveBranch("mastr", List.of("master", "develop", "feature/new"), "master");
        assertEquals("master", res.resolvedBranch());
        assertTrue(res.wasCorrected());
        assertTrue(res.explanation().contains("likely typo"));
    }

    @Test
    void testDefaultBranchFallback() {
        // Requested "master", but only "main" exists in repo
        var res = BranchResolver.resolveBranch("master", List.of("main", "develop"), "main");
        assertEquals("main", res.resolvedBranch());
        assertTrue(res.wasCorrected());
        assertTrue(res.explanation().contains("Resolved to default branch"));
    }
}

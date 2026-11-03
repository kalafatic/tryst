package eu.kalafatic.tryst.tests;

import eu.kalafatic.tryst.domain.review.DiffModel;
import eu.kalafatic.tryst.domain.review.DiffParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class DiffParserTest {

    private static final String SAMPLE_GIT_DIFF =
            "diff --git a/src/main/App.java b/src/main/App.java\n" +
            "index 1111111..2222222 100644\n" +
            "--- a/src/main/App.java\n" +
            "+++ b/src/main/App.java\n" +
            "@@ -1,5 +1,6 @@\n" +
            " package main;\n" +
            " public class App {\n" +
            "-    public void run() {}\n" +
            "+    public void run() {\n" +
            "+        System.out.println(\"hello\");\n" +
            "+    }\n" +
            " }\n";

    @Test
    public void testParseUnifiedDiffBasic() {
        DiffModel.ParsedDiff parsed = DiffParser.parse(SAMPLE_GIT_DIFF);
        assertNotNull(parsed);
        assertEquals(1, parsed.totalFiles());
        assertEquals(3, parsed.totalAdditions());
        assertEquals(1, parsed.totalDeletions());

        DiffModel.FileDiff fd = parsed.files().get(0);
        assertEquals("src/main/App.java", fd.oldPath());
        assertEquals("src/main/App.java", fd.newPath());
        assertEquals(3, fd.additions());
        assertEquals(1, fd.deletions());
        assertEquals(1, fd.modifications());
        assertEquals(1, fd.hunks().size());

        DiffModel.DiffHunk hunk = fd.hunks().get(0);
        assertTrue(hunk.header().startsWith("@@ -1,5 +1,6 @@"));

        List<DiffModel.DiffLine> lines = hunk.lines();
        assertFalse(lines.isEmpty());
        // Verify line numbering
        assertEquals(DiffModel.LineType.CONTEXT, lines.get(0).type());
        assertEquals(1, lines.get(0).oldLineNumber());
        assertEquals(1, lines.get(0).newLineNumber());

        // Verify side-by-side rows
        List<DiffModel.SideBySideRow> sbs = hunk.sideBySideRows();
        assertFalse(sbs.isEmpty());
    }

    @Test
    public void testDistinguishNewlyIntroducedLinesAcrossRevisions() {
        String revision1Diff = SAMPLE_GIT_DIFF;
        String revision2Diff =
                "diff --git a/src/main/App.java b/src/main/App.java\n" +
                "--- a/src/main/App.java\n" +
                "+++ b/src/main/App.java\n" +
                "@@ -1,5 +1,7 @@\n" +
                " package main;\n" +
                " public class App {\n" +
                "-    public void run() {}\n" +
                "+    public void run() {\n" +
                "+        System.out.println(\"hello\");\n" +
                "+        System.out.println(\"world\");\n" +
                "+    }\n" +
                " }\n";

        DiffModel.ParsedDiff parsedRev2 = DiffParser.parse(revision2Diff, revision1Diff);
        DiffModel.FileDiff fd = parsedRev2.files().get(0);
        DiffModel.DiffHunk hunk = fd.hunks().get(0);

        List<DiffModel.DiffLine> addLines = hunk.lines().stream()
                .filter(l -> l.type() == DiffModel.LineType.ADD)
                .toList();

        assertEquals(4, addLines.size());
        // System.out.println("world"); was NOT in revision1, so isNewlyIntroduced should be true
        DiffModel.DiffLine worldLine = addLines.stream()
                .filter(l -> l.content().contains("world"))
                .findFirst()
                .orElseThrow();
        assertTrue(worldLine.isNewlyIntroduced());

        // System.out.println("hello"); WAS in revision1, so isNewlyIntroduced should be false
        DiffModel.DiffLine helloLine = addLines.stream()
                .filter(l -> l.content().contains("hello"))
                .findFirst()
                .orElseThrow();
        assertFalse(helloLine.isNewlyIntroduced());
    }
}

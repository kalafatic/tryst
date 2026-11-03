package eu.kalafatic.tryst.domain.review;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DiffParser {

    private static final Pattern HUNK_HEADER_PATTERN =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$");

    public static DiffModel.ParsedDiff parse(String diffText) {
        return parse(diffText, null);
    }

    public static DiffModel.ParsedDiff parse(String currentDiffText, String previousDiffText) {
        if (currentDiffText == null || currentDiffText.trim().isEmpty()) {
            return new DiffModel.ParsedDiff(0, 0, 0, List.of());
        }

        Set<String> previousAddedLines = extractAddedLines(previousDiffText);

        List<DiffModel.FileDiff> files = new ArrayList<>();
        String[] lines = currentDiffText.split("\r?\n");

        int totalAdditions = 0;
        int totalDeletions = 0;

        int i = 0;
        while (i < lines.length) {
            String line = lines[i];

            if (line.startsWith("diff --git ")) {
                String oldPath = "";
                String newPath = "";
                int fileAdditions = 0;
                int fileDeletions = 0;
                List<DiffModel.DiffHunk> hunks = new ArrayList<>();

                i++;
                while (i < lines.length && !lines[i].startsWith("diff --git ")) {
                    String subLine = lines[i];

                    if (subLine.startsWith("--- a/")) {
                        oldPath = subLine.substring(6);
                    } else if (subLine.startsWith("--- ")) {
                        oldPath = subLine.substring(4);
                    } else if (subLine.startsWith("+++ b/")) {
                        newPath = subLine.substring(6);
                    } else if (subLine.startsWith("+++ ")) {
                        newPath = subLine.substring(4);
                    } else if (subLine.startsWith("@@ ")) {
                        Matcher matcher = HUNK_HEADER_PATTERN.matcher(subLine);
                        if (matcher.find()) {
                            int oldStart = Integer.parseInt(matcher.group(1));
                            int oldLen = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 1;
                            int newStart = Integer.parseInt(matcher.group(3));
                            int newLen = matcher.group(4) != null ? Integer.parseInt(matcher.group(4)) : 1;
                            String header = subLine;

                            List<DiffModel.DiffLine> hunkLines = new ArrayList<>();
                            List<RawLine> rawHunkLines = new ArrayList<>();

                            int currOldLine = oldStart;
                            int currNewLine = newStart;

                            i++;
                            while (i < lines.length && !lines[i].startsWith("diff --git ") && !lines[i].startsWith("@@ ")) {
                                String hl = lines[i];
                                if (hl.startsWith("+")) {
                                    String code = hl.substring(1);
                                    boolean newlyIntroduced = previousDiffText != null && !previousAddedLines.contains(code.trim());
                                    hunkLines.add(new DiffModel.DiffLine(-1, currNewLine, DiffModel.LineType.ADD, code, newlyIntroduced));
                                    rawHunkLines.add(new RawLine(DiffModel.LineType.ADD, -1, currNewLine, code, newlyIntroduced));
                                    currNewLine++;
                                    fileAdditions++;
                                } else if (hl.startsWith("-")) {
                                    String code = hl.substring(1);
                                    hunkLines.add(new DiffModel.DiffLine(currOldLine, -1, DiffModel.LineType.DELETE, code, false));
                                    rawHunkLines.add(new RawLine(DiffModel.LineType.DELETE, currOldLine, -1, code, false));
                                    currOldLine++;
                                    fileDeletions++;
                                } else if (hl.startsWith(" ") || hl.isEmpty()) {
                                    String code = hl.isEmpty() ? "" : hl.substring(1);
                                    hunkLines.add(new DiffModel.DiffLine(currOldLine, currNewLine, DiffModel.LineType.CONTEXT, code, false));
                                    rawHunkLines.add(new RawLine(DiffModel.LineType.CONTEXT, currOldLine, currNewLine, code, false));
                                    currOldLine++;
                                    currNewLine++;
                                } else if (hl.startsWith("\\ No newline at end of file")) {
                                    // ignore
                                } else {
                                    break;
                                }
                                i++;
                            }

                            List<DiffModel.SideBySideRow> sideBySide = buildSideBySide(rawHunkLines);
                            hunks.add(new DiffModel.DiffHunk(oldStart, oldLen, newStart, newLen, header, hunkLines, sideBySide));
                            continue;
                        }
                    }
                    i++;
                }

                if (newPath.isEmpty() && !oldPath.isEmpty()) {
                    newPath = oldPath;
                }
                if (oldPath.isEmpty() && !newPath.isEmpty()) {
                    oldPath = newPath;
                }

                int modifications = Math.min(fileAdditions, fileDeletions);
                files.add(new DiffModel.FileDiff(oldPath, newPath, fileAdditions, fileDeletions, modifications, hunks));
                totalAdditions += fileAdditions;
                totalDeletions += fileDeletions;
            } else {
                i++;
            }
        }

        return new DiffModel.ParsedDiff(files.size(), totalAdditions, totalDeletions, files);
    }

    private static Set<String> extractAddedLines(String diffText) {
        Set<String> set = new HashSet<>();
        if (diffText == null) {
            return set;
        }
        for (String line : diffText.split("\r?\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                set.add(line.substring(1).trim());
            }
        }
        return set;
    }

    private static List<DiffModel.SideBySideRow> buildSideBySide(List<RawLine> rawLines) {
        List<DiffModel.SideBySideRow> rows = new ArrayList<>();
        int idx = 0;
        int n = rawLines.size();

        while (idx < n) {
            RawLine cur = rawLines.get(idx);
            if (cur.type == DiffModel.LineType.CONTEXT) {
                rows.add(new DiffModel.SideBySideRow(
                        cur.oldLine, cur.content, DiffModel.LineType.CONTEXT,
                        cur.newLine, cur.content, DiffModel.LineType.CONTEXT,
                        false
                ));
                idx++;
            } else if (cur.type == DiffModel.LineType.DELETE) {
                List<RawLine> deletes = new ArrayList<>();
                while (idx < n && rawLines.get(idx).type == DiffModel.LineType.DELETE) {
                    deletes.add(rawLines.get(idx));
                    idx++;
                }
                List<RawLine> adds = new ArrayList<>();
                while (idx < n && rawLines.get(idx).type == DiffModel.LineType.ADD) {
                    adds.add(rawLines.get(idx));
                    idx++;
                }

                int max = Math.max(deletes.size(), adds.size());
                for (int m = 0; m < max; m++) {
                    RawLine d = m < deletes.size() ? deletes.get(m) : null;
                    RawLine a = m < adds.size() ? adds.get(m) : null;

                    int lNum = d != null ? d.oldLine : -1;
                    String lContent = d != null ? d.content : "";
                    DiffModel.LineType lType = d != null ? DiffModel.LineType.DELETE : DiffModel.LineType.EMPTY;

                    int rNum = a != null ? a.newLine : -1;
                    String rContent = a != null ? a.content : "";
                    DiffModel.LineType rType = a != null ? DiffModel.LineType.ADD : DiffModel.LineType.EMPTY;
                    boolean isNew = a != null && a.isNewlyIntroduced;

                    rows.add(new DiffModel.SideBySideRow(lNum, lContent, lType, rNum, rContent, rType, isNew));
                }
            } else if (cur.type == DiffModel.LineType.ADD) {
                rows.add(new DiffModel.SideBySideRow(
                        -1, "", DiffModel.LineType.EMPTY,
                        cur.newLine, cur.content, DiffModel.LineType.ADD,
                        cur.isNewlyIntroduced
                ));
                idx++;
            } else {
                idx++;
            }
        }

        return rows;
    }

    private static class RawLine {
        final DiffModel.LineType type;
        final int oldLine;
        final int newLine;
        final String content;
        final boolean isNewlyIntroduced;

        RawLine(DiffModel.LineType type, int oldLine, int newLine, String content, boolean isNewlyIntroduced) {
            this.type = type;
            this.oldLine = oldLine;
            this.newLine = newLine;
            this.content = content;
            this.isNewlyIntroduced = isNewlyIntroduced;
        }
    }
}

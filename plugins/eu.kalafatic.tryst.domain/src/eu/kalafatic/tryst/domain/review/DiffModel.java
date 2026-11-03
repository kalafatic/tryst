package eu.kalafatic.tryst.domain.review;

import java.util.List;

public final class DiffModel {

    public enum LineType {
        CONTEXT,
        ADD,
        DELETE,
        HUNK,
        EMPTY
    }

    public record DiffLine(
            int oldLineNumber,
            int newLineNumber,
            LineType type,
            String content,
            boolean isNewlyIntroduced
    ) {}

    public record SideBySideRow(
            int leftLineNumber,
            String leftContent,
            LineType leftType,
            int rightLineNumber,
            String rightContent,
            LineType rightType,
            boolean isNewlyIntroduced
    ) {}

    public record DiffHunk(
            int oldStart,
            int oldLength,
            int newStart,
            int newLength,
            String header,
            List<DiffLine> lines,
            List<SideBySideRow> sideBySideRows
    ) {}

    public record FileDiff(
            String oldPath,
            String newPath,
            int additions,
            int deletions,
            int modifications,
            List<DiffHunk> hunks
    ) {}

    public record ParsedDiff(
            int totalFiles,
            int totalAdditions,
            int totalDeletions,
            List<FileDiff> files
    ) {}

    private DiffModel() {}
}

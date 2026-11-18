package com.github.enerccio.joi.domain.memory;

import com.github.enerccio.joi.domain.model.impl.Summary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Which summaries a prompt shows of the past: a longer summary covers the shorter ones in its time (a month its weeks
 * and days, a week its days), so those are skipped and only the summaries no other one covers are left.
 */
public final class SummaryCoverage {

    private SummaryCoverage() {
    }

    /**
     * @param summaries summaries of one assistant, in any order
     * @return the ones no summary of a higher rank covers (a summary of the same rank never hides another), oldest first
     */
    public static List<Summary> uncovered(List<Summary> summaries) {
        List<Summary> byRank = new ArrayList<>(summaries);
        byRank.sort(Comparator.comparingInt((Summary s) -> s.getSummaryType().getRank()).reversed());
        List<Summary> kept = new ArrayList<>();
        for (Summary candidate : byRank) {
            boolean covered = kept.stream().anyMatch(longer ->
                    longer.getSummaryType().getRank() > candidate.getSummaryType().getRank() && longer.covers(candidate));
            if (!covered) {
                kept.add(candidate);
            }
        }
        kept.sort(Comparator.comparing(Summary::getCoverFrom).thenComparing(Summary::getCoverTo));
        return kept;
    }
}

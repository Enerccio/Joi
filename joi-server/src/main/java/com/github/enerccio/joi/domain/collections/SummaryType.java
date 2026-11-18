package com.github.enerccio.joi.domain.collections;

/**
 * How much time a {@code Summary} covers. A longer summary is made from the shorter ones of its time (a week from its
 * days) through a prompt of its own, and once it exists it covers them: a prompt takes the longest summary of a time
 * and skips the shorter ones inside it (see {@code SummaryCoverage}). Stored by name, so the names never change.
 */
public enum SummaryType {

    /** The oldest turns of a long live conversation, summarized when the prompt nears its budget. */
    ROLLING(0),
    /** One day, from day boundary to day boundary. */
    DAY(1),
    WEEK(2),
    MONTH(3),
    YEAR(4);

    private final int rank;

    SummaryType(int rank) {
        this.rank = rank;
    }

    /** The higher the rank, the longer the time: a summary of a higher rank covers the lower ones in its time. */
    public int getRank() {
        return rank;
    }
}

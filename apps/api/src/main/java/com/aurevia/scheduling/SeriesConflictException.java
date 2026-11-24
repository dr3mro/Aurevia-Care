package com.aurevia.scheduling;

import java.util.List;
import java.util.stream.Collectors;

import com.aurevia.api.ApiException;

/**
 * Rejection of a whole series when one or more occurrences overlap a protected
 * resource (CLN-01). The complete clash list is carried so the caller can see
 * every conflict, and the message renders the same list for the HTTP error body.
 */
public final class SeriesConflictException extends ApiException {

    private final List<SeriesClash> clashes;

    /**
     * Create the conflict with at least one clashing occurrence.
     *
     * @param clashes clashing occurrences, in series order
     */
    public SeriesConflictException(List<SeriesClash> clashes) {
        super(409, "SERIES_CONFLICT", describe(clashes));
        this.clashes = List.copyOf(clashes);
    }

    /**
     * Return the immutable list of clashing occurrences.
     *
     * @return clashes in series order
     */
    public List<SeriesClash> clashes() {
        return clashes;
    }

    private static String describe(List<SeriesClash> clashes) {
        return "series conflicts at " + clashes.stream()
                .map(clash -> "occurrence " + clash.occurrence() + " starting " + clash.startsAt())
                .collect(Collectors.joining(", "));
    }
}

package com.aurevia.staffmsg.mail;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure mail rules: subject normalization, reply-all addressing and confirmation
 * checking.
 *
 * <p>The rules have no state and no I/O so every branch is exercised by a small
 * deterministic test. Nothing here decides authorization; the service applies
 * tenant and participation checks around these helpers.</p>
 */
public final class MailRules {

    private static final Pattern SUBJECT_PREFIX = Pattern.compile("^(?i)(re|fwd)\\s*:\\s*");

    private MailRules() {
    }

    /**
     * Normalize a subject for thread grouping by removing repeated reply and
     * forward prefixes and case-folding the remainder.
     *
     * @param subject raw subject line
     * @return the normalized subject used as the thread label
     */
    public static String normalizeSubject(String subject) {
        String value = subject.trim();
        String previous;
        do {
            previous = value;
            value = SUBJECT_PREFIX.matcher(value).replaceFirst("");
        } while (!value.equals(previous));
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * Build the reply-all recipient list: the original sender and every original
     * recipient except the person who is replying, preserving first-seen order.
     *
     * @param recipients original sender followed by the original resolved recipients
     * @param actor      subject replying, always removed from the result
     * @return the deduplicated reply-all recipients
     */
    public static List<String> replyAll(List<String> recipients, String actor) {
        Set<String> result = new LinkedHashSet<>(recipients);
        result.remove(actor);
        return List.copyOf(result);
    }

    /**
     * Compare the list the sender confirmed with the recipients expanded at send
     * time. Order and duplicate entries are ignored; a missing or extra distinct
     * recipient fails the comparison.
     *
     * @param confirmed recipients shown to and accepted by the sender
     * @param expanded  recipients resolved from the draft at send time
     * @return {@code true} only when no confirmation is missing and both sets match
     */
    public static boolean confirmationMatches(List<String> confirmed, List<String> expanded) {
        return confirmed != null && new HashSet<>(confirmed).equals(new HashSet<>(expanded));
    }
}

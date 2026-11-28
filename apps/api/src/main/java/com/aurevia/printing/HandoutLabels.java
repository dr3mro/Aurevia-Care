package com.aurevia.printing;

import java.util.List;
import java.util.Map;

/**
 * Approved English and Arabic labels for the fixed handout header fields.
 *
 * <p>Only the stable field keys used by the renderer are present; an unknown
 * key is a programming error and never reaches the handout. The labels are
 * self-hosted strings, so no translation service or runtime resource bundle is
 * needed for a printed PDF.</p>
 */
public final class HandoutLabels {

    private static final Map<String, List<String>> LABELS = Map.of(
            "patient", List.of("Patient", "المريض"),
            "patientId", List.of("Patient ID", "معرف المريض"),
            "clinician", List.of("Clinician", "الطبيب"),
            "facility", List.of("Facility", "المنشأة"),
            "issued", List.of("Issued", "وقت الإصدار"),
            "provenance", List.of("Source", "المصدر"));

    private HandoutLabels() {
    }

    /**
     * Return one localized field label.
     *
     * @param key      stable internal field key
     * @param language {@code en} or {@code ar}
     * @return the label in the requested language
     */
    public static String label(String key, String language) {
        return LABELS.get(key).get("ar".equals(language) ? 1 : 0);
    }
}

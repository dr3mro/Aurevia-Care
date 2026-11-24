package com.aurevia.printing.chart;

import java.util.List;
import java.util.Map;

import com.aurevia.chart.record.PresenceState;

/**
 * Approved English and Arabic labels for the consolidated chart extract (UX-01).
 *
 * <p>Labels are self-hosted strings keyed by a stable internal name, so a PDF
 * can be produced without a translation service. Only the fixed vocabulary used
 * by the extract is present; an unknown key is a programming error.</p>
 */
public final class ChartLabels {

    private static final Map<String, List<String>> LABELS = Map.ofEntries(
            Map.entry("name", List.of("Patient", "المريض")),
            Map.entry("patientId", List.of("Patient ID", "معرف المريض")),
            Map.entry("identifier", List.of("Chart no.", "رقم الملف")),
            Map.entry("dob", List.of("Date of birth", "تاريخ الميلاد")),
            Map.entry("clinician", List.of("Clinician", "الطبيب")),
            Map.entry("facility", List.of("Facility", "المنشأة")),
            Map.entry("scopeVisit", List.of("Visit", "الزيارة")),
            Map.entry("scopeRange", List.of("Period", "الفترة")),
            Map.entry("section", List.of("Section", "القسم")),
            Map.entry("present", List.of("present", "موجود")),
            Map.entry("absent", List.of("absent", "غير موجود")),
            Map.entry("unknown", List.of("unknown", "غير معروف")),
            Map.entry("value", List.of("Value", "القيمة")),
            Map.entry("time", List.of("Time", "الوقت")),
            Map.entry("signedBy", List.of("Signed by", "وُقّع بواسطة")),
            Map.entry("released", List.of("Released", "تاريخ الإصدار")));

    private ChartLabels() {
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

    /**
     * Return the localized wording of a clinical presence assertion.
     *
     * @param presence clinical assertion
     * @param language {@code en} or {@code ar}
     * @return localized presence wording
     */
    public static String presence(PresenceState presence, String language) {
        if (presence == PresenceState.PRESENT) {
            return label("present", language);
        }
        if (presence == PresenceState.ABSENT) {
            return label("absent", language);
        }
        return label("unknown", language);
    }
}

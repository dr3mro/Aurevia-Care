package com.aurevia.printing;

/**
 * The five patient handout kinds produced by the printing module.
 *
 * <p>The wire key is the stable subject type stamped into a rendered PDF. The
 * English and Arabic titles are the approved handout labels; the rendering
 * boundary chooses the visual direction from the requested language.</p>
 */
public enum HandoutKind {

    /** A locally printable prescription from an attested record. */
    PRESCRIPTION("prescription", "Prescription", "وصفة طبية"),
    /** A placed laboratory order requisition. */
    LAB_ORDER("lab_order", "Laboratory order", "طلب تحليل"),
    /** A placed imaging order requisition. */
    IMAGING_ORDER("imaging_order", "Imaging order", "طلب تصوير"),
    /** A signed chart summary. */
    CHART_SUMMARY("chart_summary", "Chart summary", "ملخص الملف الطبي"),
    /** A signed laboratory or radiology report. */
    SIGNED_REPORT("signed_report", "Signed report", "تقرير موقع");

    private final String wire;
    private final String englishTitle;
    private final String arabicTitle;

    /**
     * Create a handout kind with its stable key and localized titles.
     *
     * @param wire         stable lowercase subject type
     * @param englishTitle approved English title
     * @param arabicTitle  approved Arabic title
     */
    HandoutKind(String wire, String englishTitle, String arabicTitle) {
        this.wire = wire;
        this.englishTitle = englishTitle;
        this.arabicTitle = arabicTitle;
    }

    /**
     * Return the stable wire key stored in a rendered handout.
     *
     * @return lowercase kind key
     */
    public String wire() {
        return wire;
    }

    /**
     * Return the approved title for one supported language.
     *
     * @param language {@code en} or {@code ar}
     * @return localized handout title
     */
    public String title(String language) {
        return "ar".equals(language) ? arabicTitle : englishTitle;
    }
}

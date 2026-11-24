package com.aurevia.catalog.dictionary;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Backend localization primitives for clinical text (UX-01, PRD-05).
 *
 * <p>This is a deterministic data and formatting kernel. Client-only rendering
 * (document and run attributes, isolation elements, logical CSS, digit-system
 * selection, screen-reader passes) is deliberately out of scope. Stored text is
 * never changed: isolation is returned as metadata for the client to apply.</p>
 */
public final class MixedScriptSupport {

    /**
     * Derive document direction from the interface locale.
     *
     * @param locale the interface locale
     * @return the derived document direction
     * @throws IllegalArgumentException when the locale is missing
     */
    public Direction directionFor(UiLocale locale) {
        if (locale == null) {
            throw new IllegalArgumentException("locale is required");
        }
        return locale.direction();
    }

    /**
     * Select a localized display name.
     *
     * @param name            recorded name forms
     * @param ui              interface locale
     * @param identityContext when {@code true}, show both recorded spellings
     * @return the selected display form
     * @throws IllegalArgumentException when the name or locale is missing
     */
    public String displayLocalName(LocalizedName name, UiLocale ui, boolean identityContext) {
        if (name == null) {
            throw new IllegalArgumentException("name is required");
        }
        if (ui == null) {
            throw new IllegalArgumentException("locale is required");
        }
        return identityContext ? name.displayBoth(ui) : name.display(ui);
    }

    /**
     * Annotate a text run without changing the stored text.
     *
     * @param text       stored localized text
     * @param identifier when {@code true}, keep the run left-to-right for codes
     * @return run metadata with the original text preserved exactly
     * @throws IllegalArgumentException when the text is missing
     */
    public MixedScriptRun annotateRun(LocalizedText text, boolean identifier) {
        if (text == null) {
            throw new IllegalArgumentException("text is required");
        }
        RunDirection direction = identifier ? RunDirection.LTR : RunDirection.AUTO;
        return new MixedScriptRun(text.text(), text.languageTag(), direction);
    }

    /**
     * Format a measurement with a non-breaking space and no unit conversion.
     *
     * @param value     source value as stored, with its source numeral system
     * @param unit      source unit symbol, returned verbatim
     * @param qualifier optional leading qualifier such as {@code <}
     * @return one formatted run with the qualifier, value and unit kept together
     * @throws IllegalArgumentException when the value or unit is missing
     */
    public String formatMeasurement(String value, String unit, String qualifier) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value is required");
        }
        if (unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("unit is required");
        }
        String prefix = qualifier == null || qualifier.isBlank() ? "" : qualifier + "\u00A0";
        return prefix + value + "\u00A0" + unit;
    }

    /**
     * Build an export payload that keeps the source language and units.
     *
     * @param text localized text
     * @param unit source unit symbol, or {@code null}
     * @return export metadata carrying the original language, text and unit
     * @throws IllegalArgumentException when the text is missing
     */
    public LocalizedExport export(LocalizedText text, String unit) {
        if (text == null) {
            throw new IllegalArgumentException("text is required");
        }
        return new LocalizedExport(text.languageTag(), text.text(), unit);
    }

    /**
     * Render a stored UTC instant in a named IANA zone without changing the instant.
     *
     * @param utcInstant ISO-8601 UTC instant
     * @param ianaZone   IANA zone identifier
     * @return the same instant viewed in the requested zone
     * @throws IllegalArgumentException when an input is missing or malformed
     */
    public ZonedDateTime instantWithZone(String utcInstant, String ianaZone) {
        if (utcInstant == null || utcInstant.isBlank()) {
            throw new IllegalArgumentException("UTC instant is required");
        }
        if (ianaZone == null || ianaZone.isBlank()) {
            throw new IllegalArgumentException("IANA zone is required");
        }
        // Parse each input separately so the caller learns which value was invalid.
        // java.time throws DateTimeException subclasses; translate them to the
        // domain error contract used by every other method in this class.
        Instant instant;
        try {
            instant = Instant.parse(utcInstant);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("invalid UTC instant: " + utcInstant, e);
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(ianaZone);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("invalid IANA zone: " + ianaZone, e);
        }
        return instant.atZone(zone);
    }

    /**
     * Parse a civil date with no zone or offset conversion.
     *
     * @param isoDate ISO-8601 civil date such as a date of birth
     * @return the parsed civil date
     * @throws IllegalArgumentException when the input is missing or malformed
     */
    public LocalDate civilDate(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) {
            throw new IllegalArgumentException("civil date is required");
        }
        try {
            return LocalDate.parse(isoDate);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("invalid civil date: " + isoDate, e);
        }
    }
}

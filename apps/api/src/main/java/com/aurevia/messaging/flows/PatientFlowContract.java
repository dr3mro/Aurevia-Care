package com.aurevia.messaging.flows;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Domain contract for accessible, RTL-aware patient flows on iOS and Android.
 *
 * <p>The types here carry no persistence or transport concerns. Every value is
 * a server-authoritative projection: the app renders the published locale,
 * direction, platform wording and approved generic notices and never decides
 * its own clinical content or authorization. The locale keys and platform keys
 * are wire-stable so both mobile platforms agree on their meaning.</p>
 */
public final class PatientFlowContract {

    private PatientFlowContract() {
    }

    /**
     * Supported application locale (UX-01).
     *
     * <p>{@code rtl()} and {@code direction()} are derived from the locale so a
     * single rule drives layout across every patient screen.</p>
     */
    public enum AppLocale {
        /** English, laid out left-to-right. */
        EN("en"),
        /** Arabic, laid out right-to-left with mixed-script support. */
        AR("ar");

        private final String wire;

        /**
         * Create a locale with its stable wire key.
         *
         */
        AppLocale(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire representation.
         *
         * @return stable lowercase locale key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Whether this locale is laid out right-to-left.
         *
         * @return true for Arabic
         */
        public boolean rtl() {
            return this == AR;
        }

        /**
         * The layout direction for this locale.
         *
         * @return {@code rtl} for Arabic, otherwise {@code ltr}
         */
        public String direction() {
            return rtl() ? "rtl" : "ltr";
        }

        /**
         * Parse a stored or supplied locale key.
         *
         * @return the matching locale
         * @throws IllegalArgumentException when the key is unknown
         */
        public static AppLocale from(String value) {
            for (AppLocale candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown locale: " + value);
        }
    }

    /**
     * Supported patient platform; each has OS-specific credential and settings
     * wording (PAT-02, PAT-03).
     */
    public enum AppPlatform {
        /** Apple iOS. */
        IOS("ios"),
        /** Google Android. */
        ANDROID("android");

        private final String wire;

        /**
         * Create a platform with its stable wire key.
         *
         */
        AppPlatform(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire representation.
         *
         * @return stable lowercase platform key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Locale-aware, platform-correct label for OS-backed credential storage.
         *
         * <p>The iOS wording matches the OS-backed Keychain store used by the
         * patient app rather than claiming a specific hardware enclave.</p>
         *
         * @return localized secure-storage wording for this platform
         */
        public String secureStorageLabel(AppLocale locale) {
            if (this == IOS) {
                return locale.rtl() ? "محمي بواسطة Keychain" : "Protected by iOS Keychain";
            }
            return locale.rtl() ? "محمي بواسطة Android Keystore" : "Protected by Android Keystore";
        }

        /**
         * Locale-aware, platform-correct label for the OS settings entry point.
         *
         * @return localized settings wording for this platform
         */
        public String settingsLabel(AppLocale locale) {
            if (this == IOS) {
                return locale.rtl() ? "افتح إعدادات iOS" : "Open iOS Settings";
            }
            return locale.rtl() ? "افتح إعدادات Android" : "Open Android system settings";
        }

        /**
         * Parse a stored or supplied platform key.
         *
         * @return the matching platform
         * @throws IllegalArgumentException when the key is unknown
         */
        public static AppPlatform from(String value) {
            for (AppPlatform candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown platform: " + value);
        }
    }

    /**
     * An approved generic push-notification kind (PAT-01, MSG-05).
     *
     * <p>None of these may carry clinical detail. The notification text is a
     * fixed, approved string; full content is retrieved only after the patient
     * authenticates.</p>
     */
    public enum NoticeKind {
        /** Appointment status changed. */
        APPOINTMENT_STATUS("appointment_status"),
        /** A new conversation message was published. */
        NEW_MESSAGE("new_message"),
        /** A new care-unit instruction was published. */
        NEW_INSTRUCTION("new_instruction"),
        /** Renewed authentication is required. */
        REAUTH_REQUIRED("reauth_required"),
        /** The account was locked. */
        ACCOUNT_LOCKED("account_locked"),
        /** A support case received a reply. */
        SUPPORT_REPLY("support_reply");

        private final String wire;

        /**
         * Create a notice kind with its stable wire key.
         *
         */
        NoticeKind(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire representation.
         *
         * @return stable lowercase notice key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Approved generic text for this notice in the given locale.
         *
         * @return clinical-detail-free notification text
         */
        public String text(AppLocale locale) {
            return switch (this) {
                case APPOINTMENT_STATUS -> locale.rtl()
                        ? "تم تحديث موعدك." : "Your appointment has been updated.";
                case NEW_MESSAGE -> locale.rtl()
                        ? "لديك رسالة جديدة." : "You have a new message.";
                case NEW_INSTRUCTION -> locale.rtl()
                        ? "لديك تعليمات جديدة." : "You have new instructions.";
                case REAUTH_REQUIRED -> locale.rtl()
                        ? "يرجى تسجيل الدخول مرة أخرى للمتابعة." : "Please sign in again to continue.";
                case ACCOUNT_LOCKED -> locale.rtl()
                        ? "تم قفل حسابك." : "Your account is locked.";
                case SUPPORT_REPLY -> locale.rtl()
                        ? "لديك رد من الدعم." : "You have a reply from support.";
            };
        }

        /**
         * Parse a stored or supplied notice key.
         *
         * @return the matching notice kind
         * @throws IllegalArgumentException when the key is unknown
         */
        public static NoticeKind from(String value) {
            for (NoticeKind candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown notice kind: " + value);
        }
    }

    /**
     * A client request for the server-authored presentation policy.
     *
     * @return the immutable query
     */
    public record PresentationQuery(String locale, String platform, String timeZone) {
    }

    /**
     * The server-authoritative presentation policy applied by the app.
     *
     * @return the immutable value
     */
    public record FlowPresentation(String organizationId, AppLocale locale, AppPlatform platform,
                                   boolean rtl, String direction, String settingsLabel,
                                   String secureStorageLabel, int policyVersion, Instant issuedAt,
                                   String timeZoneContext, String accessibilityProfile,
                                   List<NoticeKind> noticeKinds) {
        /**
         * Defensively copy the approved-notice list.
         *
         */
        public FlowPresentation {
            noticeKinds = List.copyOf(noticeKinds);
        }

        /**
         * Return the immutable approved-notice list.
         *
         * @return approved notice kinds
         */
        @Override
        public List<NoticeKind> noticeKinds() {
            return List.copyOf(noticeKinds);
        }
    }

    /**
     * One recorded, non-sensitive patient-flow access fact.
     *
     * @param organizationId owning organization
     * @param subject        authenticated actor
     * @param purpose        recorded business purpose from the request context
     * @param action         action key, which also names the access scope
     * @param correlationId  request correlation identifier
     * @param outcome        {@code SUCCESS}, {@code DENIED} or {@code INVALID_INPUT}
     * @param occurredAt     server timestamp
     * @return the immutable fact
     */
    public record FlowAudit(String organizationId, String subject, String purpose, String action,
                            String correlationId, String outcome, Instant occurredAt) {
    }
}

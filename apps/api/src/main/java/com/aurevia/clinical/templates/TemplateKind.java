package com.aurevia.clinical.templates;

/**
 * The five clinical note template families provided in V1 (CLN-04).
 */
public enum TemplateKind {
    /** Subjective, objective, assessment and plan note. */
    SOAP,
    /** Operative note: procedure, date, team, findings, complications and follow-up. */
    OPERATIVE,
    /** Obstetrics and gynaecology note: LMP and dictionary-backed obstetric history. */
    OBGYN,
    /** Perinatal note: birth history and neonatal history. */
    PERINATAL,
    /** Pediatric development note: milestone domain, observation and date. */
    PEDIATRIC_DEVELOPMENT
}

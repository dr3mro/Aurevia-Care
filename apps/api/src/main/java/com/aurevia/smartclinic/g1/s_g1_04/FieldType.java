package com.aurevia.smartclinic.g1.s_g1_04;

/**
 * Value kind of one template field, used to select validation rules.
 */
public enum FieldType {
    /** Free narrative text; carries a language tag on each value. */
    FREE_TEXT,
    /** Calendar date carried as an ISO-8601 civil date. */
    DATE,
    /** Coded value that must be a member of an approved field dictionary. */
    CODE,
    /** Short bounded text such as a procedure name or a team roster. */
    TEXT
}

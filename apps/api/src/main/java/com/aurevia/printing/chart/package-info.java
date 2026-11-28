/**
 * Printable consolidated chart extraction.
 *
 * <p>This package owns section selection in declaration order, inclusion rules
 * for signed notes and released results, exclusion reasons for withheld facts,
 * the bounded source identifier and the content checksum. It is reached through
 * its service, not an HTTP controller.</p>
 *
 * <p>The package must not modify chart records, treat a source digest as a
 * patient identifier, or include unsigned or entered-in-error content.</p>
 */
package com.aurevia.printing.chart;

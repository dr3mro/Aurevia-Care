package com.aurevia.smartclinic.exchange.hl7;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Guards the HL7 v2 source files against unresolved Git merge markers.
 *
 * <p>Conflict markers can be committed inside block comments, where they escape
 * compilation and documentation checks but still violate the repository rule
 * that a checked-out tree carries no conflict markers. Each test reads one
 * source file and fails if any line begins with a conflict marker, so a future
 * bad merge is caught by the ordinary unit-test suite.</p>
 */
class Hl7SourceHygieneTest {

    /** A Git conflict marker at the start of a line, including the bare separator. */
    private static final Pattern CONFLICT_MARKER = Pattern.compile("^(<<<<<<< |=======$|>>>>>>> )");

    /** Package-relative location of the HL7 sources under the API module. */
    private static final String SOURCE_DIR = "src/main/java/com/aurevia/smartclinic/exchange/hl7/";

    /**
     * Assert that the parse-exception source carries no conflict markers.
     *
     * @throws IOException when the source file cannot be read
     */
    @Test
    void hl7ParseExceptionHasNoConflictMarkers() throws IOException {
        assertNoConflictMarkers("Hl7ParseException.java");
    }

    /**
     * Assert that the parser source carries no conflict markers.
     *
     * @throws IOException when the source file cannot be read
     */
    @Test
    void hl7ParserHasNoConflictMarkers() throws IOException {
        assertNoConflictMarkers("Hl7Parser.java");
    }

    /**
     * Assert that the segment source carries no conflict markers.
     *
     * @throws IOException when the source file cannot be read
     */
    @Test
    void hl7SegmentHasNoConflictMarkers() throws IOException {
        assertNoConflictMarkers("Hl7Segment.java");
    }

    /**
     * Fail with the offending lines when a source file still contains markers.
     *
     * @param fileName simple name of the HL7 source file to inspect
     * @throws IOException when the source file cannot be read
     */
    private static void assertNoConflictMarkers(String fileName) throws IOException {
        Path source = locate(fileName);
        List<String> marked = Files.readAllLines(source).stream()
                .filter(line -> CONFLICT_MARKER.matcher(line).find())
                .toList();
        assertTrue(marked.isEmpty(),
                () -> source + " still carries conflict markers: " + marked);
    }

    /**
     * Locate a source file whether Maven starts at the module or the repository root.
     *
     * @param fileName simple name of the HL7 source file
     * @return an existing path to that source file
     */
    private static Path locate(String fileName) {
        for (String candidate : List.of(SOURCE_DIR + fileName,
                "apps/api/" + SOURCE_DIR + fileName,
                "../apps/api/" + SOURCE_DIR + fileName)) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        String basedir = System.getProperty("basedir");
        if (basedir != null) {
            Path path = Path.of(basedir, SOURCE_DIR + fileName).normalize();
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new IllegalStateException(
                "cannot locate HL7 source " + fileName + " from " + Path.of("").toAbsolutePath());
    }
}

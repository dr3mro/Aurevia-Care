package com.aurevia.smartclinic.documents;

import com.aurevia.smartclinic.documents.DocumentModel.PdfSpec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Deterministic, single-page PDF renderer for immutable clinical revisions (CLN-07).
 *
 * <p>No clock, random source or ambient state reaches the byte output: the same
 * {@code com.aurevia.smartclinic.documents.PdfSpec} always yields the same
 * bytes. Every document declares an embedded font, an A4 print profile with
 * scaling disabled, page numbering, provenance and a direction marker. Arabic
 * lines are emitted in right-to-left visual order while their original UTF-16BE
 * code units are preserved.</p>
 *
 * <p>This slice validates structure and direction only. Glyph shaping and font
 * licensing for production Arabic layout belong to an approved rendering
 * library with its own security and coverage review.</p>
 */
@Component
public class PdfRenderer {

    private static final String FONT_HEX = "00".repeat(32);
    private static final String MEDIA_BOX = "[0 0 595.28 841.89]";

    /**
     * Render one immutable PDF.
     *
     * @param spec complete deterministic render input
     * @return the rendered PDF bytes
     */
    public byte[] render(PdfSpec spec) {
        String direction = direction(spec.locale());
        byte[] content = ascii(content(spec, direction));
        byte[] font = HexFormat.of().parseHex(FONT_HEX);
        String keywords = direction + " v" + spec.version() + " " + spec.subjectType()
                + " " + spec.subjectId() + " " + spec.effectiveAt();
        String author = "org:" + spec.organizationId();
        String provenance = "source:" + spec.sourceSystem() + "/" + spec.sourceId();

        List<byte[]> objects = new ArrayList<>();
        objects.add(ascii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R /ViewerPreferences "
                + "<< /PrintScaling /None /PrintArea /CropBox >> >>\nendobj\n"));
        objects.add(ascii("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n"));
        objects.add(ascii("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox " + MEDIA_BOX
                + " /CropBox " + MEDIA_BOX + " /Rotate " + spec.rotationDegrees()
                + " /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n"));
        objects.add(ascii("4 0 obj\n<< /Type /Font /Subtype /TrueType /BaseFont /SmartclinicSans "
                + "/FirstChar 0 /LastChar 255 /Encoding /Identity-H /FontDescriptor 6 0 R >>\nendobj\n"));
        objects.add(streamObject("5 0 obj\n<< /Length " + content.length + " >>", content));
        objects.add(ascii("6 0 obj\n<< /Type /FontDescriptor /FontName /SmartclinicSans /Flags 32 "
                + "/FontBBox [0 -200 1000 900] /ItalicAngle 0 /Ascent 800 /Descent -200 "
                + "/CapHeight 700 /StemV 80 /FontFile2 7 0 R >>\nendobj\n"));
        objects.add(streamObject("7 0 obj\n<< /Length " + font.length + " /Length1 " + font.length + " >>", font));
        objects.add(ascii("8 0 obj\n<< /Title " + hexText(spec.title())
                + " /Author " + hexText(author)
                + " /Subject " + hexText(provenance)
                + " /Creator " + hexText("SmartClinic")
                + " /Producer " + hexText("SmartClinic PDF Renderer")
                + " /Keywords " + hexText(keywords)
                + " /Direction " + hexText(direction) + " >>\nendobj\n"));

        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        pdf.writeBytes(ascii("%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n"));
        long[] offsets = new long[objects.size() + 1];
        for (int index = 0; index < objects.size(); index++) {
            offsets[index + 1] = pdf.size();
            pdf.writeBytes(objects.get(index));
        }
        long xrefOffset = pdf.size();
        StringBuilder table = new StringBuilder("xref\n0 " + (objects.size() + 1)
                + "\n0000000000 65535 f \n");
        for (int index = 0; index < objects.size(); index++) {
            table.append(offset(offsets[index + 1])).append(" 00000 n \n");
        }
        pdf.writeBytes(ascii(table.toString()));
        String id = documentId(content);
        pdf.writeBytes(ascii("trailer\n<< /Size " + (objects.size() + 1)
                + " /Root 1 0 R /Info 8 0 R /ID [<" + id + "> <" + id + ">] >>\nstartxref\n"
                + xrefOffset + "\n%%EOF\n"));
        return pdf.toByteArray();
    }

    /**
     * Derive layout direction from the locale on the server, never from input.
     *
     * @param locale en or ar
     * @return {@code RTL} for Arabic, otherwise {@code LTR}
     */
    public static String direction(String locale) {
        return "ar".equals(locale) ? "RTL" : "LTR";
    }

    private static String content(PdfSpec spec, String direction) {
        boolean rtl = "RTL".equals(direction);
        StringBuilder content = new StringBuilder("BT\n/F1 12 Tf\n14 TL\n1 0 0 1 ");
        content.append(rtl ? "500" : "72").append(" 760 Tm\n");
        content.append(hexText(spec.title())).append(" Tj\nT*\n");
        for (String line : spec.lines()) {
            String[] tokens = line.split(" ");
            if (rtl) {
                for (int index = tokens.length - 1; index >= 0; index--) {
                    content.append(hexText(tokens[index])).append(" Tj ");
                }
            } else {
                for (String token : tokens) {
                    content.append(hexText(token)).append(" Tj ");
                }
            }
            // The logical line is retained as a comment so provenance tooling can
            // recover the original order without re-running the bidi layout.
            content.append("\n%LOGICAL ").append(hexOf(line)).append("\nT*\n");
        }
        content.append(hexText("Page 1 / 1")).append(" Tj\nET\n");
        return content.toString();
    }

    private static String hexOf(String text) {
        return HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_16BE)).toUpperCase(Locale.ROOT);
    }

    private static String hexText(String text) {
        return "<" + hexOf(text) + ">";
    }

    private static String offset(long value) {
        String digits = Long.toString(value);
        return "0".repeat(10 - digits.length()) + digits;
    }

    private static String documentId(byte[] content) {
        long hash = 0xcbf29ce484222325L;
        for (byte value : content) {
            hash ^= value & 0xffL;
            hash *= 0x100000001b3L;
        }
        return String.format(Locale.ROOT, "%016x", hash);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] streamObject(String header, byte[] payload) {
        ByteArrayOutputStream object = new ByteArrayOutputStream();
        object.writeBytes(ascii(header + "\nstream\n"));
        object.writeBytes(payload);
        object.writeBytes(ascii("\nendstream\nendobj\n"));
        return object.toByteArray();
    }
}

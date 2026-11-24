package com.aurevia.api.contract;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Canonical byte-length-prefixed UTF-8 parts shared by request digests and cursors.
 *
 * <p>Each part is written as a four-byte big-endian byte count followed by its
 * UTF-8 bytes. Length prefixes keep boundaries unambiguous when a payload or
 * identifier contains mixed-script text, so two different requests cannot
 * collide merely because a delimiter moved (UX-01, ARC-03). Decoding is bounded
 * so an attacker-supplied cursor cannot allocate unbounded memory.</p>
 */
final class ByteParts {

    private static final int LENGTH_BYTES = 4;
    static final int MAX_PART_BYTES = 256;

    private ByteParts() {
    }

    /**
     * Encode parts into a canonical byte stream.
     *
     * @param parts non-null parts in a fixed, caller-defined order
     * @return the length-prefixed UTF-8 stream
     */
    static byte[] encode(String... parts) {
        byte[][] values = new byte[parts.length][];
        int total = 0;
        for (int index = 0; index < parts.length; index++) {
            Objects.requireNonNull(parts[index], "part");
            values[index] = parts[index].getBytes(StandardCharsets.UTF_8);
            total += LENGTH_BYTES + values[index].length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(total);
        for (byte[] value : values) {
            buffer.putInt(value.length).put(value);
        }
        return buffer.array();
    }

    /**
     * Decode a canonical stream into exactly the expected number of parts.
     *
     * @param payload encoded stream
     * @param expectedParts number of parts the caller requires
     * @return immutable list of decoded parts
     * @throws IllegalArgumentException when the stream is truncated, declares an
     *         impossible part length, or contains trailing bytes
     */
    static List<String> decode(byte[] payload, int expectedParts) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length < LENGTH_BYTES * expectedParts) {
            throw new IllegalArgumentException("payload is truncated");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        List<String> parts = new ArrayList<>(expectedParts);
        for (int index = 0; index < expectedParts; index++) {
            int length = buffer.getInt();
            if (length < 0 || length > MAX_PART_BYTES || buffer.remaining() < length) {
                throw new IllegalArgumentException("invalid part length");
            }
            byte[] value = new byte[length];
            buffer.get(value);
            parts.add(new String(value, StandardCharsets.UTF_8));
        }
        if (buffer.hasRemaining()) {
            throw new IllegalArgumentException("trailing bytes in payload");
        }
        return List.copyOf(parts);
    }
}

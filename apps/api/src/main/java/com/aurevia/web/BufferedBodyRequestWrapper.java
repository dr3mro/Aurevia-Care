package com.aurevia.web;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Replays an already-captured request body to downstream handlers (ARC-03).
 *
 * <p>The idempotency capture filter reads the body once so it can hash it before
 * the handler runs. The original servlet stream cannot be rewound, so this
 * wrapper gives every later reader a fresh stream over the same bytes. It is
 * package-private because only that filter creates it; no body is copied into
 * a log or a durable ledger row.</p>
 */
final class BufferedBodyRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] body;

    /**
     * Wrap a request with an immutable snapshot of its body.
     *
     * @param request the original servlet request
     * @param body bytes already read from that request
     */
    BufferedBodyRequestWrapper(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream source = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return source.read();
            }

            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                // The body is fully buffered; asynchronous reads are not needed.
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}

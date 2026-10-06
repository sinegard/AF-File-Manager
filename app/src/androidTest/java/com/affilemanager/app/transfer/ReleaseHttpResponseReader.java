package com.affilemanager.app.transfer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Bounded client for the length-delimited responses used by release fixtures. */
public final class ReleaseHttpResponseReader {
    private ReleaseHttpResponseReader() {}

    public static String read(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int terminator = 0;
        do {
            if (output.size() >= Math.min(16 * 1024, maximumBytes)) throw new IOException("Fixture header bound exceeded");
            int next = input.read();
            if (next < 0) throw new EOFException("Incomplete fixture response headers");
            output.write(next);
            terminator = (terminator << 8) | next;
        } while (terminator != 0x0d0a0d0a);

        String[] lines = output.toString("ISO-8859-1").split("\r\n");
        if (!lines[0].startsWith("HTTP/1.1 ")) throw new IOException("Unexpected fixture response version");
        Long length = null;
        for (int line = 1; line < lines.length; line++) {
            int colon = lines[line].indexOf(':');
            if (colon <= 0) throw new IOException("Malformed fixture response header");
            String name = lines[line].substring(0, colon).toLowerCase(Locale.ROOT);
            if (name.equals("transfer-encoding")) throw new IOException("Unexpected fixture transfer encoding");
            if (name.equals("content-length")) {
                if (length != null) throw new IOException("Ambiguous fixture response length");
                String value = lines[line].substring(colon + 1).trim();
                if (!value.matches("[0-9]+")) throw new IOException("Invalid fixture response length");
                try { length = Long.parseLong(value); }
                catch (NumberFormatException invalid) { throw new IOException("Invalid fixture response length", invalid); }
            }
        }
        if (length == null) throw new IOException("Missing fixture response length");
        if (length > maximumBytes - output.size()) throw new IOException("Fixture response bound exceeded");
        byte[] buffer = new byte[4096];
        long remaining = length;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) throw new EOFException("Incomplete fixture response body");
            if (count == 0) throw new IOException("Fixture response made no progress");
            output.write(buffer, 0, count);
            remaining -= count;
        }
        // Complete message framing, not TCP EOF, decides whether the response is valid.
        return output.toString("UTF-8");
    }

    public static void verifyContract() throws IOException {
        String complete = "HTTP/1.1 401 Unauthorized\r\nContent-Length: 7\r\n\r\nfixture";
        require(complete.equals(read(resetAfter(complete), 4096)), "Length-delimited response does not need TCP EOF");
        String empty = "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n";
        require(empty.equals(read(resetAfter(empty), 4096)), "Empty response does not need TCP EOF");
        expectIncomplete("HTTP/1.1 401 Unauthorized\r\nContent-Length: 8\r\n\r\nfixture", 4096);
        expectIncomplete("HTTP/1.1 401 Unauthorized\r\nContent-Len", 4096);
        expectIncomplete("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nContent-Length: 0\r\n\r\n", 4096);
        expectIncomplete("HTTP/1.1 401 Unauthorized\r\nTransfer-Encoding: chunked\r\nContent-Length: 0\r\n\r\n", 4096);
        expectIncomplete("HTTP/1.1 401 Unauthorized\r\nContent-Length: 99999\r\n\r\n", 128);
    }

    private static InputStream resetAfter(String response) {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        return new InputStream() {
            private int position;
            @Override public int read() throws IOException {
                if (position == bytes.length) throw new SocketException("Synthetic reset after complete response");
                return bytes[position++] & 0xff;
            }
        };
    }

    private static void expectIncomplete(String response, int maximumBytes) throws IOException {
        try {
            read(new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)), maximumBytes);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Incomplete, ambiguous or oversized fixture response was accepted");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

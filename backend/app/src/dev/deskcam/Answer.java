package dev.deskcam;

import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

/**
 * What an endpoint produced, before anything has decided how to send it.
 *
 * The handlers used to write straight to the socket: `sendJson`, `sendBytes` and
 * `Tar.writeTo` inside the routing switch. That works exactly once, for the shape of
 * answer HTTP wants, and it is why a script could not call them. A verb of card 57 has to
 * put a still into a multipart stream, and the alternative to this class is a second copy
 * of every handler that renders the other way, which is two code paths and one of them
 * would fall behind.
 *
 * So a handler returns a value and the caller renders it. `/api/still` renders one of
 * these as an image response with its provenance header; a script renders the same object
 * as a JSON event followed by an image part. Card 57.
 */
final class Answer {

    /** How the thing came out, which decides how HTTP presents it. */
    enum Shape {
        /** A JSON document. */
        JSON,
        /** One file, sent as itself. */
        FILE,
        /** Several files, sent as one tar. */
        ARCHIVE
    }

    final Shape shape;
    /** The HTTP status. 206 for a burst that ran short; 200 otherwise. */
    final int status;
    /** The content type of a FILE. Null for the other two shapes. */
    final String contentType;
    /** Extra response headers, already terminated with CRLF each. Never null. */
    final String headers;
    /**
     * The JSON body of a JSON answer, or the record that describes the files of the other
     * two: the provenance of a still, the manifest of a walk. Never null.
     *
     * A script puts this in the event that precedes the file parts, which is how a capture
     * inside a tape carries the same record it carries as its own response.
     */
    final JSONObject record;
    /** The name of each file, in order. Empty for JSON. */
    final List<String> names;
    /** The bytes of each file, in the same order. */
    final List<byte[]> files;

    private Answer(Shape shape, int status, String contentType, String headers,
                   JSONObject record, List<String> names, List<byte[]> files) {
        this.shape = shape;
        this.status = status;
        this.contentType = contentType;
        this.headers = headers == null ? "" : headers;
        this.record = record == null ? new JSONObject() : record;
        this.names = names == null ? Collections.emptyList() : names;
        this.files = files == null ? Collections.emptyList() : files;
    }

    static Answer json(JSONObject body) {
        return new Answer(Shape.JSON, 200, null, "", body, null, null);
    }

    static Answer file(String name, String contentType, byte[] bytes, JSONObject record,
                       String headers) {
        return new Answer(Shape.FILE, 200, contentType, headers, record,
                Collections.singletonList(name), Collections.singletonList(bytes));
    }

    static Answer archive(int status, List<String> names, List<byte[]> files,
                          JSONObject record, String headers) {
        return new Answer(Shape.ARCHIVE, status, "application/x-tar", headers, record,
                names, files);
    }

    /** How many bytes of picture this carries, for the request log. */
    long byteCount() {
        long n = 0;
        for (byte[] f : files) n += f.length;
        return n;
    }
}

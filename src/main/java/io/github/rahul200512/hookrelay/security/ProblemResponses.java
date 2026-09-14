package io.github.rahul200512.hookrelay.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectMapper;

/** Writes RFC 9457 bodies from places Spring MVC's advice can't reach (filters, entry points). */
final class ProblemResponses {

    static final String TYPE_BASE = "https://github.com/Rahul200512/hookrelay/blob/main/docs/problems.md#";

    private ProblemResponses() {}

    static void write(HttpServletResponse response, ObjectMapper mapper, HttpStatus status, String slug, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + slug));
        problem.setTitle(status.getReasonPhrase());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // Write bytes, not characters: the servlet writer would encode as ISO-8859-1 and
        // mangle any non-ASCII detail, and it would stamp a charset the MVC layer's own
        // problem responses don't carry.
        response.getOutputStream().write(mapper.writeValueAsString(problem).getBytes(StandardCharsets.UTF_8));
    }
}

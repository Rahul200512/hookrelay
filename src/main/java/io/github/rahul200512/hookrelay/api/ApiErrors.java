package io.github.rahul200512.hookrelay.api;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

/**
 * Every error is an RFC 9457 problem with a stable {@code type} URI that points at the
 * docs. Spring turns these into {@code application/problem+json} on its own.
 */
public final class ApiErrors {

    static final String TYPE_BASE = "https://github.com/Rahul200512/hookrelay/blob/main/docs/problems.md#";

    private ApiErrors() {}

    private static URI type(String slug) {
        return URI.create(TYPE_BASE + slug);
    }

    public static class Problem extends ErrorResponseException {
        Problem(HttpStatus status, String slug, String detail) {
            super(status);
            setType(type(slug));
            setTitle(status.getReasonPhrase());
            setDetail(detail);
        }
    }

    public static class NotFound extends Problem {
        public NotFound(String what) {
            super(HttpStatus.NOT_FOUND, "not-found", what + " not found.");
        }
    }

    public static class InvalidTarget extends Problem {
        public InvalidTarget(String reason) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-target", reason);
        }
    }

    public static class LimitReached extends Problem {
        public LimitReached(String detail) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "limit-reached", detail);
        }
    }

    public static class TooManyRequests extends Problem {
        public TooManyRequests(String detail) {
            super(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", detail);
        }
    }

    public static class NotReplayable extends Problem {
        public NotReplayable(String detail) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, "not-replayable", detail);
        }
    }

    public static class BadRequest extends Problem {
        public BadRequest(String detail) {
            super(HttpStatus.BAD_REQUEST, "bad-request", detail);
        }
    }
}

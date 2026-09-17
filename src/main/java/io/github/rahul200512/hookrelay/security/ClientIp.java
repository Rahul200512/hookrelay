package io.github.rahul200512.hookrelay.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Who a request is actually from, as far as anything behind a proxy can know.
 *
 * <p>{@code getRemoteAddr()} on its own is the address of whatever spoke to us last. In
 * this deployment that is a load balancer whose address rotates, which quietly turned the
 * signup limiter into no limiter at all: every request looked like a new client, so the
 * count never reached the threshold. It passed locally, where there is no proxy, and did
 * nothing in production. That is the failure mode of anything keyed on an identity you
 * did not check.
 *
 * <p>{@code server.forward-headers-strategy} does not save you either: Tomcat only
 * rewrites the remote address when the immediate peer matches its trusted-proxy ranges,
 * and an edge network's addresses are not in them.
 *
 * <p>So the headers are read directly, most trustworthy first. Honest limitation:
 * {@code X-Forwarded-For} is written by the client and can say anything. Nothing here is
 * a security boundary — it blunts a script and protects a free-tier database, and the
 * real backstop is that demo tenants are deleted after a week.
 */
public final class ClientIp {

    private ClientIp() {}

    public static String of(HttpServletRequest request) {
        // Set by the edge in front of this service and overwritten on every request,
        // so it cannot be forged by the caller.
        String edge = request.getHeader("CF-Connecting-IP");
        if (edge != null && !edge.isBlank()) {
            return edge.trim();
        }
        // Leftmost entry is the original client, if everyone in the chain is honest.
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}

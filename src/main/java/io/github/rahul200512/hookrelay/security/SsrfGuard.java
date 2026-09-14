package io.github.rahul200512.hookrelay.security;

import io.github.rahul200512.hookrelay.config.HookrelayProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;

/**
 * A webhook target is a URL we will POST to from inside our network, on behalf of a
 * stranger. Without this check the service is an open proxy into cloud metadata
 * endpoints and anything else on the private side.
 *
 * <p>Checked at registration and again immediately before every send, because DNS can
 * change between the two.
 */
@Component
public class SsrfGuard {

    private final boolean allowPrivateTargets;

    public SsrfGuard(HookrelayProperties properties) {
        this.allowPrivateTargets = properties.security().allowPrivateTargets();
    }

    public static final class ForbiddenTargetException extends RuntimeException {
        public ForbiddenTargetException(String message) {
            super(message);
        }
    }

    /** @throws ForbiddenTargetException with a message safe to show to the caller */
    public URI validate(String raw) {
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ForbiddenTargetException("URL is not valid.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("https") && !(allowPrivateTargets && scheme.equals("http"))) {
            throw new ForbiddenTargetException("URL must use https.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ForbiddenTargetException("URL must have a host.");
        }
        if (uri.getUserInfo() != null) {
            throw new ForbiddenTargetException("URL must not embed credentials.");
        }
        if (allowPrivateTargets) {
            return uri;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new ForbiddenTargetException("Host does not resolve.");
        }
        for (InetAddress address : addresses) {
            if (isPrivate(address)) {
                throw new ForbiddenTargetException("Host resolves to a private or reserved address.");
            }
        }
        return uri;
    }

    static boolean isPrivate(InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                || a.isAnyLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            // 100.64.0.0/10 carrier-grade NAT, 0.0.0.0/8, 192.0.0.0/24, 240.0.0.0/4
            if (first == 100 && second >= 64 && second <= 127) return true;
            if (first == 0) return true;
            if (first == 192 && second == 0 && (b[2] & 0xff) == 0) return true;
            if (first >= 240) return true;
            return false;
        }
        // IPv6 unique local fc00::/7
        return (b[0] & 0xfe) == 0xfc;
    }
}

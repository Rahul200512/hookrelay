package io.github.rahul200512.hookrelay.security;

import io.github.rahul200512.hookrelay.domain.ApiKeyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code Authorization: Bearer hr_live_...}. A missing header falls through to Spring
 * Security, which answers 401 for protected paths and lets public ones pass. A
 * present-but-wrong key is answered here, immediately, so a bad key never reaches a
 * public endpoint looking anonymous.
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final ApiKeyRepository apiKeys;
    private final ObjectMapper mapper;

    public ApiKeyAuthenticationFilter(ApiKeyRepository apiKeys, ObjectMapper mapper) {
        this.apiKeys = apiKeys;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            chain.doFilter(request, response);
            return;
        }
        String raw = header.substring(BEARER.length()).trim();
        var key = apiKeys.findByKeyHashAndRevokedAtIsNull(ApiKeys.hash(raw));
        if (key.isEmpty()) {
            ProblemResponses.write(response, mapper, HttpStatus.UNAUTHORIZED, "invalid-api-key",
                    "The API key is unknown or has been revoked.");
            return;
        }
        var principal = new TenantPrincipal(key.get().getTenantId(), key.get().getId());
        var auth = new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_TENANT")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        chain.doFilter(request, response);
    }
}

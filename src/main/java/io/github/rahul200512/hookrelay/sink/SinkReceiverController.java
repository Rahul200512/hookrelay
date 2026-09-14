package io.github.rahul200512.hookrelay.sink;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The public side of a sink: unauthenticated, because a real receiver would be too. */
@RestController
public class SinkReceiverController {

    private final SinkService sinks;

    public SinkReceiverController(SinkService sinks) {
        this.sinks = sinks;
    }

    @PostMapping("/sink/{token}")
    public ResponseEntity<Map<String, Object>> receive(@PathVariable String token,
                                                       @RequestBody(required = false) String body,
                                                       HttpServletRequest request) {
        Map<String, String> headers = new TreeMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase();
            if (lower.equals("authorization") || lower.equals("cookie")) continue;
            headers.put(lower, request.getHeader(name));
        }
        if (!sinks.receive(token, headers, body == null ? "" : body)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("received", true));
    }
}

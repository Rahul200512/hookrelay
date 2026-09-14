package io.github.rahul200512.hookrelay.it;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * A receiver whose behaviour a test scripts: a sequence of status codes to answer with,
 * the last one repeating. Records everything it gets.
 */
@RestController
public class FakeReceiver {

    public record Received(Map<String, String> headers, String body) {}

    private final Map<String, Deque<Integer>> scripts = new ConcurrentHashMap<>();
    private final Map<String, List<Received>> received = new ConcurrentHashMap<>();
    private final Map<String, Long> delaysMs = new ConcurrentHashMap<>();

    public void script(String key, int... statuses) {
        Deque<Integer> d = new ArrayDeque<>();
        for (int s : statuses) d.add(s);
        scripts.put(key, d);
        received.put(key, new CopyOnWriteArrayList<>());
    }

    public void delay(String key, long millis) {
        delaysMs.put(key, millis);
    }

    public List<Received> received(String key) {
        return received.getOrDefault(key, List.of());
    }

    @PostMapping("/fake/{key}")
    public ResponseEntity<String> handle(@PathVariable String key, @RequestBody(required = false) String body,
                                         @RequestHeader HttpHeaders headers) throws InterruptedException {
        received.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(new Received(headers.toSingleValueMap(), body));
        long delay = delaysMs.getOrDefault(key, 0L);
        if (delay > 0) Thread.sleep(delay);
        Deque<Integer> script = scripts.get(key);
        int status = script == null || script.isEmpty() ? 200 : (script.size() > 1 ? script.poll() : script.peek());
        var response = ResponseEntity.status(status);
        if (status == 429) response.header("Retry-After", "1");
        return response.body("{\"ok\":" + (status < 300) + "}");
    }
}

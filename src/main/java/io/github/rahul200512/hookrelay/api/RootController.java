package io.github.rahul200512.hookrelay.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sends the root at the documentation.
 *
 * <p>Without this, opening the service in a browser answers a bare 401 problem document
 * — correct, since nothing is published at the root, and a terrible first thing to show
 * someone who was handed the link. An API whose front door reads as an error is one
 * people assume is broken.
 */
@RestController
class RootController {

    @Operation(summary = "Redirects to the API documentation")
    @SecurityRequirements
    @GetMapping("/")
    ResponseEntity<Void> root() {
        return ResponseEntity.status(302).location(URI.create("/swagger-ui.html")).build();
    }
}

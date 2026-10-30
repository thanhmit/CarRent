package com.carrent.payment;

import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SePayWebhookController {
    private final SePayWebhookService webhooks;
    public SePayWebhookController(SePayWebhookService webhooks) { this.webhooks = webhooks; }

    @PostMapping(value = "/webhooks/sepay/{channelId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Boolean> receive(@PathVariable Long channelId, @RequestHeader HttpHeaders headers,
                                       @RequestBody byte[] body) {
        webhooks.receive(channelId, headers, body);
        return Map.of("success", true);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Boolean>> authentication(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("success", false));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Boolean>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("success", false));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Boolean>> retry() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("success", false));
    }
}

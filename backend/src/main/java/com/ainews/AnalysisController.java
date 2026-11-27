package com.ainews;

import java.net.URI;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
class AnalysisController {
    private final AnalysisJobs jobs;

    AnalysisController(AnalysisJobs jobs) {
        this.jobs = jobs;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        jobs.checkDatabase();
        return Map.of("status", "ok");
    }

    @PostMapping(value = "/analyses", consumes = "multipart/form-data")
    ResponseEntity<Map<String, String>> create(
        @RequestParam String type, @RequestParam(defaultValue = "") String headline,
        @RequestParam(defaultValue = "") String text, @RequestParam(defaultValue = "") String url,
        @RequestParam(required = false) MultipartFile image) throws java.io.IOException {
        Map<String, String> submission = switch (type) {
            case "text" -> {
                if (headline.length() > 300 || text.length() > 20_000 ||
                    java.util.regex.Pattern.compile("[\\p{L}\\p{N}]+").matcher(text).results().limit(4).count() < 4) {
                    throw bad("Enter at least four words and up to 20,000 characters. The headline limit is 300 characters.");
                }
                yield Map.of("type", type, "headline", headline.strip(), "text", text.strip());
            }
            case "url" -> {
                if (url.length() > 2048 || !ReportScorer.isWebUrl(url.strip())) {
                    throw bad("Enter a valid public HTTP or HTTPS article link.");
                }
                yield Map.of("type", type, "url", url.strip());
            }
            case "image" -> {
                if (image == null || image.isEmpty() || image.getSize() > 5 * 1024 * 1024) {
                    throw bad("Choose a PNG, JPEG, or WebP up to 5 MB.");
                }
                byte[] bytes = image.getBytes();
                if (!isImage(bytes)) throw bad("Choose a PNG, JPEG, or WebP image.");
                yield Map.of("type", type, "image", Base64.getEncoder().encodeToString(bytes));
            }
            default -> throw bad("Choose text, an image, or an article link.");
        };
        String id = jobs.submit(submission);
        return ResponseEntity.accepted().location(URI.create("/api/analyses/" + id))
            .body(Map.of("id", id, "state", "Queued"));
    }

    @GetMapping("/analyses/{id}")
    Map<String, Object> get(@PathVariable UUID id) {
        return jobs.get(id.toString());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> requestError(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", ex.getReason()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> tooLarge() {
        return ResponseEntity.status(413).body(Map.of("message", "Choose an image up to 5 MB."));
    }

    private static boolean isImage(byte[] bytes) {
        if (bytes.length < 12) return false;
        return ((bytes[0] & 255) == 137 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') ||
               ((bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255) ||
               (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F' &&
                bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P');
    }

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}


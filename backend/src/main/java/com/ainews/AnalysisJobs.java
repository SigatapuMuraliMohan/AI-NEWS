package com.ainews;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
class AnalysisJobs {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ReportScorer scorer;
    private final RestClient ai;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
        2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8)
    );

    AnalysisJobs(JdbcTemplate db, ObjectMapper json, ReportScorer scorer,
                 @Value("${news.ai-url}") String aiUrl, @Value("${INTERNAL_TOKEN}") String token) {
        this.db = db;
        this.json = json;
        this.scorer = scorer;
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(150));
        ai = RestClient.builder().baseUrl(aiUrl).requestFactory(factory)
            .defaultHeader("X-Internal-Token", token).build();
    }

    @PostConstruct
    void recoverInterruptedJobs() {
        db.update("UPDATE analyses SET state='Failed', stage='Failed', error=? WHERE state IN ('Queued','Running')",
            "The application restarted before the report was completed. Please submit again.");
    }

    @PreDestroy
    void stop() {
        workers.shutdownNow();
    }

    void checkDatabase() {
        db.queryForObject("SELECT 1", Integer.class);
    }

    String submit(Map<String, String> input) {
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO analyses(id, state, stage) VALUES (?, 'Queued', 'Queued')", id);
        try {
            workers.execute(() -> run(id, input));
        } catch (RejectedExecutionException ex) {
            db.update("DELETE FROM analyses WHERE id=?", id);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Analysis is busy. Please try again shortly.");
        }
        return id;
    }

    Map<String, Object> get(String id) {
        try {
            return db.queryForObject("SELECT * FROM analyses WHERE id=?", (rs, row) -> {
                Map<String, Object> status = new LinkedHashMap<>();
                status.put("id", id);
                status.put("state", rs.getString("state"));
                status.put("stage", rs.getString("stage"));
                status.put("error", rs.getString("error"));
                String report = rs.getString("report");
                status.put("report", report == null ? null : parse(report));
                return status;
            }, id);
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This analysis could not be found.");
        }
    }

    private void run(String id, Map<String, String> input) {
        try {
            stage(id, "Extracting text and claims");
            JsonNode prepared = invoke("prepare", input);
            stage(id, "Finding sources");
            JsonNode search = invoke("search", prepared);
            stage(id, "Comparing claims");
            JsonNode findings = invoke("compare", Map.of("article", prepared.required("article"),
                "plan", prepared.required("plan"), "search", search));
            var report = scorer.score(prepared, search, findings);
            db.update("UPDATE analyses SET state='Completed', stage='Completed', report=? WHERE id=?",
                json.writeValueAsString(report), id);
        } catch (Exception ex) {
            String message = ex instanceof AnalysisFailure ? ex.getMessage()
                : "Analysis could not finish. Please retry and check that the analysis service is running.";
            LoggerFactory.getLogger(AnalysisJobs.class).warn("Analysis {} failed ({})", id,
                ex.getClass().getSimpleName());
            db.update("UPDATE analyses SET state='Failed', stage='Failed', error=? WHERE id=?", message, id);
        }
    }

    private void stage(String id, String stage) {
        db.update("UPDATE analyses SET state='Running', stage=? WHERE id=?", stage, id);
    }

    private JsonNode invoke(String method, Object input) {
        try {
            JsonNode response = ai.post().uri("/internal/" + method).body(input).retrieve().body(JsonNode.class);
            if (response == null || !response.isObject()) throw new AnalysisFailure("Invalid analysis service response.");
            return response;
        } catch (RestClientResponseException ex) {
            String detail = parse(ex.getResponseBodyAsString()).path("detail").asText("");
            if (detail.isBlank() || detail.length() > 300) detail = "The analysis service rejected the request.";
            throw new AnalysisFailure(detail);
        }
    }

    private JsonNode parse(String data) {
        try {
            return json.readTree(data);
        } catch (JsonProcessingException ex) {
            throw new AnalysisFailure("The analysis service returned unreadable data.");
        }
    }

    private static class AnalysisFailure extends RuntimeException {
        AnalysisFailure(String message) { super(message); }
    }
}


package com.ainews;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class ReportScorer {
    private static final Set<String> VERDICTS = Set.of("Supported", "Contradicted", "Missing Context",
        "Insufficient Evidence");
    private static final Set<String> SOURCE_TYPES = Set.of("official notice", "original study", "public record",
        "news report", "other");
    private static final Set<String> BIAS = Set.of("loaded_language", "one_sided_framing");

    record Source(String id, String title, String publisher, String date, String url,
                  String passage, String type, String why) {}
    record Verdict(String claim, String verdict, String explanation, List<String> evidenceIds, int riskPoints) {}
    record Finding(String category, String severity, String field, String excerpt, String reason,
                   int start, int end, List<String> evidenceIds, int riskPoints, int biasPoints) {}

    private final String version;
    private final int[] thresholds;
    private final Map<String, Integer> weights = new LinkedHashMap<>();
    private final int contradiction;
    private final int missingContext;

    ReportScorer(@Value("${news.rubric.version}") String version,
                 @Value("${news.rubric.thresholds}") int[] thresholds,
                 @Value("${news.rubric.weights}") String weights,
                 @Value("${news.rubric.contradiction}") int contradiction,
                 @Value("${news.rubric.missing-context}") int missingContext) {
        if (thresholds.length != 3 || thresholds[0] <= 0 || thresholds[0] >= thresholds[1] ||
            thresholds[1] >= thresholds[2] || thresholds[2] > 100) {
            throw new IllegalArgumentException("Expected three increasing risk thresholds between 1 and 100");
        }
        this.version = version;
        this.thresholds = thresholds;
        this.contradiction = contradiction;
        this.missingContext = missingContext;
        for (String entry : weights.split(",")) {
            String[] pair = entry.split(":");
            this.weights.put(pair[0], Integer.parseInt(pair[1]));
        }
        if (this.weights.values().stream().anyMatch(weight -> weight < 0 || weight > 100) ||
            contradiction < 0 || contradiction > 100 || missingContext < 0 || missingContext > 100) {
            throw new IllegalArgumentException("Rubric weights must be between 0 and 100");
        }
    }

    Map<String, Object> score(JsonNode prepared, JsonNode search, JsonNode ai) {
        JsonNode article = prepared.required("article");
        JsonNode plan = prepared.required("plan");
        String headline = str(article, "headline", 300);
        String body = str(article, "text", 20000);
        if (body.isBlank()) throw new IllegalArgumentException("Missing extracted text");
        boolean headlineCheck = !headline.isBlank() && body.split("\\s+").length >= 20;
        var sources = sources(search.path("evidence"), ai.path("sources"));
        var claims = verdicts(plan.path("claims"), ai.path("verdicts"), sources.keySet());
        var findings = findings(ai.path("findings"), headline, body, headlineCheck, sources.keySet());
        int covered = (int) claims.stream().filter(c -> !c.evidenceIds().isEmpty()).count();
        Integer credibility = covered > 0 && covered * 2 >= claims.size()
            ? (int) Math.round(claims.stream().mapToInt(c -> switch (c.verdict()) {
                case "Supported" -> 100;
                case "Missing Context" -> 50;
                default -> 0;
            }).sum() / (double) covered) : null;
        int risk = Math.min(100, findings.stream().mapToInt(Finding::riskPoints).sum() +
            claims.stream().mapToInt(Verdict::riskPoints).sum());
        int bias = Math.min(100, findings.stream().mapToInt(Finding::biasPoints).sum());
        String level = risk < thresholds[0] ? "Neutral" : risk < thresholds[1] ? "Mild Bias" :
            risk < thresholds[2] ? "Moderate Manipulation" : "High Manipulation Risk";
        Set<String> cited = new HashSet<>();
        claims.forEach(c -> cited.addAll(c.evidenceIds()));
        findings.forEach(f -> cited.addAll(f.evidenceIds()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("article", Map.of("headline", headline, "text", body, "publisher", str(article, "publisher", 300),
            "date", str(article, "date", 100), "url", safeUrl(article)));
        result.put("claims", claims);
        result.put("findings", findings);
        result.put("sources", sources.values().stream().filter(source -> cited.contains(source.id())).toList());
        result.put("keywords", plan.path("keywords"));
        result.put("entities", plan.path("entities"));
        String searchStatus = str(search, "status", 30);
        result.put("searchStatus", Set.of("available", "empty", "unconfigured", "unavailable").contains(searchStatus)
            ? searchStatus : "unavailable");
        result.put("headlineCheck", headlineCheck ? "Assessed" : "Not assessed");
        result.put("limited", covered == 0 ? "Language-only assessment" :
            covered < claims.size() ? "Partial evidence coverage" : "");
        result.put("scores", Map.of("risk", risk, "bias", bias, "level", level, "covered", covered,
            "total", claims.size()));
        result.put("credibility", credibility);
        result.put("rubric", Map.of("version", version, "thresholds", thresholds, "weights", weights,
            "contradiction", contradiction, "missingContext", missingContext));
        return result;
    }

    private List<Verdict> verdicts(JsonNode claims, JsonNode results, Set<String> sourceIds) {
        List<Verdict> verdicts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < Math.min(claims.size(), 3); index++) {
            String claim = str(claims.get(index), "claim", 500).strip();
            if (claim.isBlank() || !seen.add(normalize(claim))) continue;
            JsonNode item = null;
            int matches = 0;
            for (JsonNode row : results) {
                if (row.path("claimIndex").isIntegralNumber() && row.path("claimIndex").asInt() == index) {
                    item = row;
                    matches++;
                }
            }
            List<String> ids = matches == 1 ? references(item, sourceIds) : List.of();
            String verdict = matches == 1 ? str(item, "verdict", 40) : "";
            String reason = matches == 1 ? str(item, "explanation", 900).strip() : "";
            if (ids.isEmpty() || reason.isBlank() || !VERDICTS.contains(verdict) ||
                verdict.equals("Insufficient Evidence")) {
                if (!verdict.equals("Insufficient Evidence") || reason.isBlank()) {
                    reason = "The retrieved sources did not establish this claim.";
                }
                verdict = "Insufficient Evidence";
                ids = List.of();
            }
            int points = switch (verdict) {
                case "Contradicted" -> contradiction;
                case "Missing Context" -> missingContext;
                default -> 0;
            };
            verdicts.add(new Verdict(claim, verdict, reason, ids, points));
        }
        return verdicts;
    }

    private List<Finding> findings(JsonNode rows, String headline, String body, boolean headlineCheck,
                                   Set<String> sourceIds) {
        List<Finding> candidates = new ArrayList<>();
        for (JsonNode row : rows) {
            String category = str(row, "category", 40);
            if (!weights.containsKey(category) || (category.equals("headline_mismatch") && !headlineCheck)) continue;
            String field = str(row, "field", 10);
            String target = field.equals("headline") ? headline : field.equals("text") ? body : "";
            String excerpt = str(row, "excerpt", 350);
            String reason = str(row, "reason", 650).strip();
            int start = target.indexOf(excerpt);
            if (excerpt.strip().length() < 3 || start < 0 || reason.isBlank()) continue;
            List<String> ids = references(row, sourceIds);
            if (ids.isEmpty() && Set.of("exaggeration", "material_omission").contains(category)) continue;
            String severity = str(row, "severity", 10);
            double multiplier = switch (severity) {
                case "low" -> 0.5;
                case "medium" -> 1;
                case "high" -> 1.5;
                default -> 0;
            };
            if (multiplier == 0) continue;
            int points = (int) Math.round(weights.get(category) * multiplier);
            candidates.add(new Finding(category, severity, field, excerpt, reason, start, start + excerpt.length(),
                ids, points, BIAS.contains(category) ? Math.min(100, points * 3) : 0));
        }
        candidates.sort(Comparator.comparingInt(Finding::riskPoints).reversed());
        List<Finding> selected = new ArrayList<>();
        for (Finding candidate : candidates) {
            if (selected.stream().anyMatch(f -> normalize(f.excerpt()).equals(normalize(candidate.excerpt())) ||
                normalize(f.reason()).equals(normalize(candidate.reason())) ||
                (f.field().equals(candidate.field()) && f.start() < candidate.end() && candidate.start() < f.end()))) {
                continue;
            }
            if (selected.stream().filter(f -> f.category().equals(candidate.category())).count() >= 2) continue;
            selected.add(candidate);
        }
        return selected;
    }

    private static Map<String, Source> sources(JsonNode rows, JsonNode assessments) {
        Map<String, Source> result = new LinkedHashMap<>();
        Set<String> urls = new HashSet<>();
        for (JsonNode row : rows) {
            String id = str(row, "id", 10);
            String url = safeUrl(row);
            String title = str(row, "title", 300);
            String passage = str(row, "passage", 3500);
            if (!id.matches("S[1-9]") || url.isBlank() || title.isBlank() || passage.length() < 100 ||
                result.containsKey(id) || !urls.add(url)) continue;
            String type = "other", why = "The report cited a passage from this source.";
            for (JsonNode assessment : assessments) {
                if (!id.equals(str(assessment, "id", 10))) continue;
                String proposed = str(assessment, "type", 30);
                if (SOURCE_TYPES.contains(proposed)) type = proposed;
                String description = str(assessment, "why", 400);
                if (!description.isBlank()) why = description;
                break;
            }
            result.put(id, new Source(id, title, str(row, "publisher", 300), str(row, "date", 100),
                url, passage, type, why));
        }
        return result;
    }

    private static List<String> references(JsonNode row, Set<String> allowed) {
        Set<String> ids = new LinkedHashSet<>();
        JsonNode refs = row.path("evidenceIds");
        if (!refs.isArray() || refs.size() > 6) return List.of();
        for (JsonNode ref : refs) {
            if (!ref.isTextual() || !allowed.contains(ref.asText())) return List.of();
            ids.add(ref.asText());
        }
        return List.copyOf(ids);
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static String str(JsonNode row, String key, int limit) {
        JsonNode value = row.path(key);
        return value.isTextual() && value.textValue().length() <= limit ? value.textValue() : "";
    }

    private static String safeUrl(JsonNode row) {
        String url = str(row, "url", 2048);
        return isWebUrl(url) ? url : "";
    }

    static boolean isWebUrl(String url) {
        try {
            URI parsed = URI.create(url);
            return ("https".equalsIgnoreCase(parsed.getScheme()) || "http".equalsIgnoreCase(parsed.getScheme())) &&
                parsed.getHost() != null && parsed.getUserInfo() == null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}


package com.gczm.aimeeting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TingwuResultParser {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public Map<String, Object> parse(Map<String, Object> taskInfo, Map<String, String> resultUrls) {
        Object inlineResult = taskInfo.get("InlineResult");

        Object transcriptSource = null;
        Object summarySource = null;

        if (inlineResult instanceof Map<?, ?> map) {
            transcriptSource = map.get("transcript");
            summarySource = map.get("summary");
        }

        if (transcriptSource == null && resultUrls.get("transcript") != null) {
            transcriptSource = loadJson(resultUrls.get("transcript"));
        }

        if (summarySource == null && resultUrls.get("summary") != null) {
            summarySource = loadJson(resultUrls.get("summary"));
        }

        List<Map<String, Object>> segments = parseTranscriptSegments(transcriptSource);
        Map<String, Object> summary = parseSummary(summarySource);

        if (resultUrls.get("chapters") != null) {
            Object chapters = loadJson(resultUrls.get("chapters"));
            if (chapters instanceof List<?> list) {
                summary.put("chapters", list);
            }
        }

        if (resultUrls.get("todos") != null) {
            Object todos = loadJson(resultUrls.get("todos"));
            if (todos instanceof List<?> list) {
                summary.put("todos", list);
            }
        }

        Map<String, Object> out = new HashMap<>();
        out.put("segments", segments);
        out.put("summary", summary);
        out.put("raw_result_refs", resultUrls);
        return out;
    }

    public List<Map<String, Object>> parseTranscriptSegments(Object payload) {
        if (payload == null) {
            return List.of();
        }

        List<?> source = null;
        if (payload instanceof List<?> list) {
            source = list;
        } else if (payload instanceof Map<?, ?> map) {
            for (String key : List.of("segments", "sentences", "SentenceList", "data")) {
                Object value = map.get(key);
                if (value instanceof List<?> list) {
                    source = list;
                    break;
                }
            }
        }

        if (source == null) {
            return List.of();
        }

        List<Map<String, Object>> normalized = new ArrayList<>();
        int idx = 0;
        for (Object item : source) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }

            String text = firstString(map, List.of("text", "Text", "content"));
            if (text == null || text.isBlank()) {
                continue;
            }

            Map<String, Object> seg = new LinkedHashMap<>();
            seg.put("segment_order", idx);
            seg.put("speaker", firstString(map, List.of("speaker", "Speaker")));
            seg.put("start_ms", firstInteger(map, List.of("start_ms", "StartTime", "begin_time")));
            seg.put("end_ms", firstInteger(map, List.of("end_ms", "EndTime", "end_time")));
            seg.put("confidence", firstInteger(map, List.of("confidence", "Confidence")));
            seg.put("text", text);
            normalized.add(seg);
            idx++;
        }
        return normalized;
    }

    public Map<String, Object> parseSummary(Object payload) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("overview", "");
        defaults.put("chapters", List.of());
        defaults.put("decisions", List.of());
        defaults.put("highlights", List.of());
        defaults.put("todos", List.of());

        if (payload == null) {
            return defaults;
        }

        if (payload instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("overview", firstString(map, List.of("overview", "Overview", "summary"), ""));
            out.put("chapters", firstList(map, List.of("chapters", "Chapters")));
            out.put("decisions", firstList(map, List.of("decisions", "Decisions")));
            out.put("highlights", firstList(map, List.of("highlights", "Highlights")));
            out.put("todos", firstList(map, List.of("todos", "Todos")));
            return out;
        }

        if (payload instanceof List<?> list) {
            Map<String, Object> out = new LinkedHashMap<>(defaults);
            out.put("highlights", list);
            return out;
        }

        return defaults;
    }

    private Object loadJson(String url) {
        try {
            // Signed OSS URLs contain encoded query params; using URI avoids template re-encoding.
            ResponseEntity<String> response = restTemplate.getForEntity(URI.create(url), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return null;
            }
            return objectMapper.readValue(response.getBody(), Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String firstString(Map<?, ?> map, List<String> keys) {
        return firstString(map, keys, null);
    }

    private String firstString(Map<?, ?> map, List<String> keys, String defaultValue) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return defaultValue;
    }

    private Integer firstInteger(Map<?, ?> map, List<String> keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof Integer i) {
                return i;
            }
            if (value instanceof Long l) {
                return l.intValue();
            }
            if (value instanceof Number n) {
                return n.intValue();
            }
            if (value instanceof String s) {
                try {
                    return Integer.parseInt(s);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private List<?> firstList(Map<?, ?> map, List<String> keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof List<?> list) {
                return list;
            }
        }
        return List.of();
    }
}

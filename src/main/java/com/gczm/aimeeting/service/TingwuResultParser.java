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

        if (payload instanceof Map<?, ?> rootMap) {
            Object transcription = rootMap.get("Transcription");
            if (transcription != null) {
                payload = transcription;
            }
        }

        List<?> source = null;
        if (payload instanceof List<?> list) {
            source = list;
        } else if (payload instanceof Map<?, ?> map) {
            for (String key : List.of("segments", "sentences", "SentenceList", "data", "Sentences", "Paragraphs", "paragraphs")) {
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

            String text = firstString(map, List.of("text", "Text", "content", "ParagraphText", "SentenceText"));
            if (text == null || text.isBlank()) {
                text = wordsToText(map.get("Words"));
            }
            if (text == null || text.isBlank()) {
                continue;
            }

            Integer startMs = firstInteger(map, List.of("start_ms", "StartTime", "begin_time", "start", "Start", "BeginTime"));
            Integer endMs = firstInteger(map, List.of("end_ms", "EndTime", "end_time", "end", "End"));
            if (startMs == null) {
                startMs = wordsStart(map.get("Words"));
            }
            if (endMs == null) {
                endMs = wordsEnd(map.get("Words"));
            }

            Map<String, Object> seg = new LinkedHashMap<>();
            seg.put("segment_order", idx);
            seg.put("speaker", firstString(map, List.of("speaker", "Speaker", "SpeakerId", "SpeakerName")));
            seg.put("start_ms", startMs);
            seg.put("end_ms", endMs);
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

        if (payload instanceof Map<?, ?> root) {
            Object summarization = root.get("Summarization");
            if (summarization != null) {
                payload = summarization;
            }
        }

        if (payload instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            String overview = firstString(map, List.of("overview", "Overview", "summary", "ParagraphSummary", "paragraphSummary"), "");
            out.put("overview", overview);

            List<?> chapters = firstList(map, List.of("chapters", "Chapters", "AutoChapters", "autoChapters"));
            if (chapters.isEmpty()) {
                String paragraphTitle = firstString(map, List.of("ParagraphTitle", "paragraphTitle"));
                if (paragraphTitle != null && !paragraphTitle.isBlank()) {
                    chapters = List.of(Map.of("title", paragraphTitle));
                }
            }
            out.put("chapters", chapters);

            List<?> decisions = firstList(map, List.of("decisions", "Decisions"));
            List<?> qaList = firstList(map, List.of("QuestionsAnsweringSummary", "questionsAnsweringSummary"));
            if (decisions.isEmpty() && !qaList.isEmpty()) {
                decisions = qaList;
            }
            out.put("decisions", decisions);

            List<?> highlights = firstList(map, List.of("highlights", "Highlights"));
            List<?> conversational = firstList(map, List.of("ConversationalSummary", "conversationalSummary"));
            if (highlights.isEmpty() && !conversational.isEmpty()) {
                highlights = conversational;
            }
            out.put("highlights", highlights);

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

    @SuppressWarnings("unchecked")
    private String wordsToText(Object wordsObject) {
        if (!(wordsObject instanceof List<?> words)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Object w : words) {
            if (w instanceof Map<?, ?> wordMap) {
                Object text = wordMap.get("Text");
                if (text == null) {
                    text = wordMap.get("text");
                }
                if (text instanceof String s && !s.isBlank()) {
                    sb.append(s);
                }
            }
        }
        return sb.toString();
    }

    private Integer wordsStart(Object wordsObject) {
        if (!(wordsObject instanceof List<?> words) || words.isEmpty()) {
            return null;
        }
        Object first = words.get(0);
        if (!(first instanceof Map<?, ?> firstWord)) {
            return null;
        }
        return firstInteger(firstWord, List.of("Start", "start", "BeginTime"));
    }

    private Integer wordsEnd(Object wordsObject) {
        if (!(wordsObject instanceof List<?> words) || words.isEmpty()) {
            return null;
        }
        Object last = words.get(words.size() - 1);
        if (!(last instanceof Map<?, ?> lastWord)) {
            return null;
        }
        return firstInteger(lastWord, List.of("End", "end", "EndTime"));
    }
}

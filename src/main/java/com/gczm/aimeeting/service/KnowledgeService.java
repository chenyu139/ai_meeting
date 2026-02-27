package com.gczm.aimeeting.service;

import com.gczm.aimeeting.config.AppProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private final AppProperties appProperties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public List<Map<String, Object>> search(String query, int topK) {
        String url = appProperties.getKnowledge().getSearchUrl();
        if (StringUtils.isBlank(url)) {
            return List.of();
        }

        String finalUrl = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("q", query)
                .queryParam("top_k", topK)
                .toUriString();

        ResponseEntity<String> response = restTemplate.getForEntity(finalUrl, String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return List.of();
        }

        try {
            Object payload = objectMapper.readValue(response.getBody(), Object.class);
            if (payload instanceof List<?> list) {
                return list.stream().filter(Map.class::isInstance).map(m -> (Map<String, Object>) m).toList();
            }
            if (payload instanceof Map<?, ?> map) {
                for (String key : List.of("items", "data", "results")) {
                    Object value = map.get(key);
                    if (value instanceof List<?> list) {
                        return list.stream().filter(Map.class::isInstance).map(m -> (Map<String, Object>) m).toList();
                    }
                }
            }
        } catch (Exception ignored) {
            return List.of();
        }

        return List.of();
    }
}

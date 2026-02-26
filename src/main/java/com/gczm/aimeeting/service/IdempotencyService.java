package com.gczm.aimeeting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gczm.aimeeting.entity.IdempotencyRecordEntity;
import com.gczm.aimeeting.mapper.IdempotencyRecordMapper;
import com.gczm.aimeeting.util.Jsons;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRecordMapper recordMapper;
    private final ObjectMapper objectMapper;

    public String buildRequestHash(Object payload) {
        Object canonical = Jsons.canonicalize(objectMapper, payload);
        String serialized = Jsons.toJson(objectMapper, canonical);
        return DigestUtils.sha256Hex(serialized);
    }

    public Optional<CachedResponse> load(String key, String endpoint, String userId, String requestHash) {
        IdempotencyRecordEntity record = recordMapper.selectById(key);
        if (record == null) {
            return Optional.empty();
        }
        if (!endpoint.equals(record.getEndpoint())
                || !userId.equals(record.getUserId())
                || !requestHash.equals(record.getRequestHash())) {
            return Optional.empty();
        }
        try {
            Map<String, Object> body = objectMapper.readValue(record.getResponseBody(), new TypeReference<>() {
            });
            return Optional.of(new CachedResponse(body, record.getStatusCode()));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public void save(String key,
                     String endpoint,
                     String userId,
                     String requestHash,
                     Map<String, Object> responseBody,
                     int statusCode) {
        IdempotencyRecordEntity existing = recordMapper.selectById(key);
        if (existing != null) {
            return;
        }

        IdempotencyRecordEntity record = new IdempotencyRecordEntity();
        record.setKey(key);
        record.setEndpoint(endpoint);
        record.setUserId(userId);
        record.setRequestHash(requestHash);
        record.setResponseBody(Jsons.toJson(objectMapper, responseBody));
        record.setStatusCode(statusCode);
        record.setCreatedAt(LocalDateTime.now());
        recordMapper.insert(record);
    }

    public void deleteAll() {
        recordMapper.delete(new LambdaQueryWrapper<>());
    }

    public record CachedResponse(Map<String, Object> body, int statusCode) {
    }
}

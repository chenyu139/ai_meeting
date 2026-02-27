package com.gczm.aimeeting.service;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.ICredentialProvider;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.tingwu20230930.AsyncClient;
import com.aliyun.sdk.service.tingwu20230930.models.CreateTaskRequest;
import com.aliyun.sdk.service.tingwu20230930.models.CreateTaskResponse;
import com.aliyun.sdk.service.tingwu20230930.models.GetTaskInfoRequest;
import com.aliyun.sdk.service.tingwu20230930.models.GetTaskInfoResponse;
import com.gczm.aimeeting.common.ApiException;
import com.gczm.aimeeting.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import darabonba.core.client.ClientOverrideConfiguration;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

@Service
@RequiredArgsConstructor
public class TingwuClient {

    private final AppProperties appProperties;

    private AsyncClient cachedClient;

    public Map<String, Object> createRealtimeTask(String taskKey, String title, String webhookUrl) {
        if (!"sdk".equalsIgnoreCase(appProperties.getTingwu().getMode())) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "TINGWU_MODE must be sdk");
        }

        CreateTaskRequest.Input input = CreateTaskRequest.Input.builder()
                .taskKey(taskKey)
                .sourceLanguage("cn")
                .format("pcm")
                .sampleRate(16000)
                .progressiveCallbacksEnabled(true)
                .build();

        CreateTaskRequest.MeetingAssistance meetingAssistance = CreateTaskRequest.MeetingAssistance.builder()
                .types(List.of("Actions", "KeyInformation"))
                .build();

        CreateTaskRequest.Summarization summarization = CreateTaskRequest.Summarization.builder()
                .types(List.of("Paragraph", "Conversational", "QuestionsAnswering"))
                .build();

        CreateTaskRequest.Transcription transcription = CreateTaskRequest.Transcription.builder()
                .diarizationEnabled(true)
                .realtimeDiarizationEnabled(true)
                .build();

        CreateTaskRequest.Parameters parameters = CreateTaskRequest.Parameters.builder()
                .meetingAssistanceEnabled(true)
                .meetingAssistance(meetingAssistance)
                .summarizationEnabled(true)
                .summarization(summarization)
                .autoChaptersEnabled(true)
                .transcription(transcription)
                .build();

        CreateTaskRequest request = CreateTaskRequest.builder()
                .appKey(appProperties.getTingwu().getAppKey())
                .type("realtime")
                .operation("start")
                .input(input)
                .parameters(parameters)
                .build();

        try {
            CreateTaskResponse response = client().createTask(request).join();
            Map<String, Object> raw = response.getBody() == null ? Map.of() : response.getBody().toMap();
            String taskId = response.getBody() == null || response.getBody().getData() == null
                    ? null : response.getBody().getData().getTaskId();
            String meetingJoinUrl = response.getBody() == null || response.getBody().getData() == null
                    ? null : response.getBody().getData().getMeetingJoinUrl();

            if (StringUtils.isBlank(taskId)) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "CreateTask missing TaskId");
            }

            Map<String, Object> result = new HashMap<>();
            result.put("task_id", taskId);
            result.put("meeting_join_url", meetingJoinUrl);
            result.put("raw", raw);
            return result;
        } catch (CompletionException ex) {
            Throwable root = ex.getCause() == null ? ex : ex.getCause();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Tingwu request failed: " + root.getMessage());
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Tingwu request failed: " + ex.getMessage());
        }
    }

    public Map<String, Object> stopTask(String taskId) {
        CreateTaskRequest.Input input = CreateTaskRequest.Input.builder()
                .taskId(taskId)
                .sourceLanguage("cn")
                .build();

        CreateTaskRequest request = CreateTaskRequest.builder()
                .appKey(appProperties.getTingwu().getAppKey())
                .type("realtime")
                .operation("stop")
                .input(input)
                .build();

        try {
            CreateTaskResponse response = client().createTask(request).join();
            String code = response.getBody() == null ? null : response.getBody().getCode();
            String message = response.getBody() == null ? null : response.getBody().getMessage();

            Map<String, Object> result = new HashMap<>();
            result.put("ok", StringUtils.isBlank(code) || "0".equals(code));
            result.put("error", message == null ? "" : message);
            result.put("raw", response.getBody() == null ? Map.of() : response.getBody().toMap());
            return result;
        } catch (CompletionException ex) {
            Throwable root = ex.getCause() == null ? ex : ex.getCause();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Tingwu request failed: " + root.getMessage());
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Tingwu request failed: " + ex.getMessage());
        }
    }

    public Map<String, Object> getTaskInfo(String taskId) {
        GetTaskInfoRequest request = GetTaskInfoRequest.builder().taskId(taskId).build();

        try {
            GetTaskInfoResponse response = client().getTaskInfo(request).join();
            var body = response.getBody();
            var data = body == null ? null : body.getData();
            var result = data == null ? null : data.getResult();

            Map<String, Object> payload = new HashMap<>();
            payload.put("TaskId", data == null ? taskId : data.getTaskId());
            payload.put("TaskStatus", data == null ? null : data.getTaskStatus());
            payload.put("Result", result == null ? Map.of() : result.toMap());
            payload.put("OutputMp3Path", data == null ? null : data.getOutputMp3Path());
            payload.put("OutputMp4Path", data == null ? null : data.getOutputMp4Path());
            payload.put("RawResponse", body == null ? Map.of() : body.toMap());
            if (data != null && data.getErrorCode() != null) {
                payload.put("ErrorCode", data.getErrorCode());
            }
            if (data != null && data.getErrorMessage() != null) {
                payload.put("ErrorMessage", data.getErrorMessage());
            }
            return payload;
        } catch (CompletionException ex) {
            Throwable root = ex.getCause() == null ? ex : ex.getCause();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Tingwu request failed: " + root.getMessage());
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Tingwu request failed: " + ex.getMessage());
        }
    }

    public String extractTaskStatus(Map<String, Object> payload) {
        String value = findString(payload, List.of("TaskStatus", "taskStatus", "task_status"));
        return value == null ? null : value.toUpperCase();
    }

    public String extractTaskId(Map<String, Object> payload) {
        return findString(payload, List.of("TaskId", "taskId", "task_id"));
    }

    public Map<String, String> extractResultUrls(Map<String, Object> taskInfo) {
        Map<String, String> candidates = new HashMap<>();
        Map<String, Object> result = asMap(taskInfo.get("Result"));

        addIfHttp(candidates, "transcript", result.get("Transcription"));
        addIfHttp(candidates, "summary", result.get("Summarization"));
        addIfHttp(candidates, "chapters", result.get("AutoChapters"));
        addIfHttp(candidates, "meeting_assistance", result.get("MeetingAssistance"));

        addIfHttp(candidates, "mp3", taskInfo.get("OutputMp3Path"));
        addIfHttp(candidates, "mp4", taskInfo.get("OutputMp4Path"));

        walkForUrls(taskInfo, "", candidates);
        return candidates;
    }

    public Map<String, Object> normalizeEvent(Map<String, Object> payload) {
        Map<String, Object> normalized = new HashMap<>();
        normalized.put("task_id", extractTaskId(payload));
        normalized.put("task_status", extractTaskStatus(payload));
        normalized.put("event_time", payload.getOrDefault("EventTime", LocalDateTime.now().toString()));
        normalized.put("raw", payload);
        return normalized;
    }

    private synchronized AsyncClient client() {
        if (cachedClient != null) {
            return cachedClient;
        }

        if (StringUtils.isAnyBlank(
                appProperties.getTingwu().getAccessKeyId(),
                appProperties.getTingwu().getAccessKeySecret(),
                appProperties.getTingwu().getAppKey())) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Missing Tingwu credentials. Set TINGWU_ACCESS_KEY_ID/SECRET and TINGWU_APP_KEY");
        }

        Credential credential = new Credential.Builder()
                .accessKeyId(appProperties.getTingwu().getAccessKeyId())
                .accessKeySecret(appProperties.getTingwu().getAccessKeySecret())
                .build();
        ICredentialProvider provider = StaticCredentialProvider.create(credential);

        ClientOverrideConfiguration overrideConfiguration = ClientOverrideConfiguration.create()
                .setProtocol("https");
        if (StringUtils.isNotBlank(appProperties.getTingwu().getEndpoint())) {
            overrideConfiguration.setEndpointOverride(appProperties.getTingwu().getEndpoint());
        }

        cachedClient = AsyncClient.builder()
                .credentialsProvider(provider)
                .region(appProperties.getTingwu().getRegion())
                .overrideConfiguration(overrideConfiguration)
                .build();
        return cachedClient;
    }

    private void walkForUrls(Object node, String path, Map<String, String> candidates) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    continue;
                }
                String lowerKey = key.toLowerCase();
                String current = path.isBlank() ? lowerKey : path + "." + lowerKey;
                Object value = entry.getValue();
                if (value instanceof String s && s.startsWith("http")) {
                    if (lowerKey.contains("summary") || current.contains("summary")) {
                        candidates.putIfAbsent("summary", s);
                    } else if (lowerKey.contains("chapter")) {
                        candidates.putIfAbsent("chapters", s);
                    } else if (lowerKey.contains("meetingassistance") || lowerKey.contains("assist")) {
                        candidates.putIfAbsent("meeting_assistance", s);
                    } else if (lowerKey.contains("trans") || lowerKey.contains("text")) {
                        candidates.putIfAbsent("transcript", s);
                    } else {
                        candidates.putIfAbsent("raw", s);
                    }
                } else {
                    walkForUrls(value, current, candidates);
                }
            }
            return;
        }

        if (node instanceof List<?> list) {
            for (Object item : list) {
                walkForUrls(item, path, candidates);
            }
        }
    }

    private String findString(Map<String, Object> payload, List<String> keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value instanceof String s && StringUtils.isNotBlank(s)) {
                return s;
            }
        }
        Object data = payload.get("Data");
        if (data instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> nested = (Map<String, Object>) map;
            return findString(nested, keys);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new HashMap<>();
    }

    private void addIfHttp(Map<String, String> out, String key, Object value) {
        if (value instanceof String s && s.startsWith("http")) {
            out.put(key, s);
        }
    }
}

package com.gczm.aimeeting.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.OSSObjectSummary;
import com.gczm.aimeeting.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import com.gczm.aimeeting.common.ApiException;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StorageService {

    private final AppProperties appProperties;

    private OSS ossClient;

    public UploadResult uploadAudioChunk(String meetingId, int chunkIndex, MultipartFile file) {
        String objectKey = "meetings/" + meetingId + "/chunks/" + chunkIndex + ".m4a";
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Failed to read upload file");
        }

        if (isOssMode()) {
            client().putObject(appProperties.getStorage().getOssBucket(), objectKey,
                    new java.io.ByteArrayInputStream(bytes));
            return new UploadResult(objectKey, bytes.length);
        }

        Path local = localFilePath(objectKey);
        try {
            Files.createDirectories(local.getParent());
            Files.write(local, bytes);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to write local audio chunk");
        }
        return new UploadResult(objectKey, bytes.length);
    }

    public String getObjectUrl(String objectKey) {
        if (isOssMode()) {
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                    appProperties.getStorage().getOssBucket(), objectKey);
            request.setExpiration(java.util.Date.from(Instant.now().plusSeconds(3600)));
            URL url = client().generatePresignedUrl(request);
            return url.toString();
        }

        String base = trimTailSlash(appProperties.getPublicBaseUrl());
        String prefix = appProperties.getApiPrefix();
        return base + prefix + "/files/" + objectKey;
    }

    public Path localFilePath(String objectKey) {
        Path root = Paths.get(appProperties.getStorage().getLocalStorageRoot()).toAbsolutePath().normalize();
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid file path");
        }
        return target;
    }

    public List<String> listMeetingChunkKeys(String meetingId) {
        String prefix = "meetings/" + meetingId + "/chunks/";
        if (isOssMode()) {
            ObjectListing listing = client().listObjects(appProperties.getStorage().getOssBucket(), prefix);
            List<String> keys = new ArrayList<>();
            for (OSSObjectSummary summary : listing.getObjectSummaries()) {
                keys.add(summary.getKey());
            }
            keys.sort(String::compareTo);
            return keys;
        }

        Path root = localFilePath(prefix);
        if (!Files.exists(root)) {
            return List.of();
        }
        try {
            List<String> keys = new ArrayList<>();
            Files.list(root)
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::getFileName))
                    .forEach(p -> keys.add(prefix + p.getFileName()));
            return keys;
        } catch (IOException e) {
            return List.of();
        }
    }

    private synchronized OSS client() {
        if (ossClient != null) {
            return ossClient;
        }
        if (!isOssMode()) {
            throw new IllegalStateException("OSS client requested in local mode");
        }

        AppProperties.Storage storage = appProperties.getStorage();
        ossClient = new OSSClientBuilder().build(storage.getOssEndpoint(),
                storage.getOssAccessKeyId(),
                storage.getOssAccessKeySecret());
        return ossClient;
    }

    private boolean isOssMode() {
        AppProperties.Storage storage = appProperties.getStorage();
        return "oss".equalsIgnoreCase(storage.getMode())
                && StringUtils.isNoneBlank(storage.getOssEndpoint(), storage.getOssBucket(),
                storage.getOssAccessKeyId(), storage.getOssAccessKeySecret());
    }

    private String trimTailSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    public record UploadResult(String objectKey, Integer sizeBytes) {
    }
}

package com.gczm.aimeeting.controller;

import com.gczm.aimeeting.common.ApiException;
import com.gczm.aimeeting.config.AppProperties;
import com.gczm.aimeeting.service.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequiredArgsConstructor
public class FileController {

    private final StorageService storageService;
    private final AppProperties appProperties;

    @GetMapping("${app.api-prefix:/api/v1}/files/**")
    public ResponseEntity<Resource> serveLocalFile(HttpServletRequest request) {
        String prefix = appProperties.getApiPrefix() + "/files/";
        String uri = request.getRequestURI();
        if (!uri.startsWith(prefix) || uri.length() <= prefix.length()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "file not found");
        }

        String filePath = uri.substring(prefix.length());
        Path path = storageService.localFilePath(filePath);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "file not found");
        }
        return ResponseEntity.ok(new FileSystemResource(path));
    }
}

package com.tang.plugin.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 公开临时文件端点（免 JWT），供 pipispy 服务端抓取用户上传的以图搜图片。
 *
 * <p>路径 /public/aisearch/{name} 不在任何受保护前缀下，因此 JwtAuthFilter 直接放行。
 * 文件名由 UUID 生成（不可猜测），且 AiImageSearchController 在 submit 成功后立即删除，
 * 生命周期极短。仅返回 aisearch-tmp 目录内的常规文件，防止路径穿越。
 */
@Slf4j
@RestController
public class PublicFileController {

    private static final Path TMP_DIR = Paths.get(System.getProperty("java.io.tmpdir"), "aisearch-tmp");

    @GetMapping("/public/aisearch/{name}")
    public ResponseEntity<Resource> serve(@PathVariable String name, HttpServletRequest request) {
        if (name == null || !name.matches("[A-Za-z0-9._-]+")) {
            return ResponseEntity.notFound().build();
        }
        Path p = TMP_DIR.resolve(name).normalize();
        try {
            if (!p.startsWith(TMP_DIR) || !Files.exists(p) || !Files.isRegularFile(p)) {
                return ResponseEntity.notFound().build();
            }
            String ct = Files.probeContentType(p);
            MediaType mt = (ct != null) ? MediaType.parseMediaType(ct) : MediaType.APPLICATION_OCTET_STREAM;
            return ResponseEntity.ok().contentType(mt).body(new InputStreamResource(Files.newInputStream(p)));
        } catch (Exception e) {
            log.warn("public aisearch file serve failed name={}", name, e);
            return ResponseEntity.status(500).build();
        }
    }
}

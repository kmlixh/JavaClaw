package com.janyee.agent.web.controller;

import com.janyee.agent.infra.auth.AuthPrincipal;
import com.janyee.agent.infra.auth.SecurityContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 流式上传大附件 —— 跟 chat 请求体解耦,避免把几百 MB 文件 base64 塞 JSON。
 *
 * <p>典型流程:</p>
 * <ol>
 *   <li>前端发 POST /api/chat/attachments?sessionId=... (multipart/form-data, field=file)</li>
 *   <li>后端流式 transferTo 到工作区 uploads/&lt;sessionId&gt;/&lt;uuid&gt;__&lt;原文件名&gt;</li>
 *   <li>响应 {name, path, contentType, size} —— path 是工作区相对路径,前端塞进 chat
 *       请求的 attachments[].path 字段</li>
 *   <li>Agent 处理消息时,doc.normalize / file.read 直接用 path 读到落盘的文件</li>
 * </ol>
 *
 * <p>不上 DB 表 —— 文件直接放工作区根的 uploads/ 子目录,文件命名带 sessionId 隔离,
 * 后续清理可以按 session 删除整个子目录。</p>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatAttachmentUploadController {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentUploadController.class);
    /** 单文件最大尺寸。1GB —— 跟 nginx client_max_body_size 1g 对齐。
     *  几百兆到 1GB 的工程方案 PDF 直接流式 transferTo 到磁盘,不进 JVM 堆。 */
    private static final long MAX_FILE_BYTES = 1024L * 1024 * 1024;

    private final String workspaceRoot;

    public ChatAttachmentUploadController(
            @Value("${agent.workspace-root:./workspaces}") String workspaceRoot
    ) {
        this.workspaceRoot = workspaceRoot;
    }

    private Path agentWorkspaceRoot(String agentId) {
        return Path.of(workspaceRoot).resolve(agentId).toAbsolutePath().normalize();
    }

    @PostMapping(value = "/attachments", consumes = "multipart/form-data")
    public Mono<Map<String, Object>> upload(
            @RequestPart("file") Mono<FilePart> filePartMono,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "agentId", required = false) String agentId
    ) {
        AuthPrincipal principal = SecurityContextHolder.current();
        String userId = principal == null ? "anonymous" : principal.userId();
        String session = sessionId == null || sessionId.isBlank()
                ? "draft-" + UUID.randomUUID().toString().substring(0, 8)
                : sessionRelpath(sessionId);
        String hostAgent = agentId == null || agentId.isBlank() ? "dev-agent" : agentId;

        return filePartMono.flatMap(filePart -> {
            String originalName = sanitizeFilename(filePart.filename());
            String storedName = UUID.randomUUID().toString().substring(0, 8) + "__" + originalName;
            Path root = agentWorkspaceRoot(hostAgent);
            Path uploadsDir = root.resolve("uploads").resolve(session);
            Path target = uploadsDir.resolve(storedName);
            try {
                Files.createDirectories(uploadsDir);
            } catch (Exception ioe) {
                return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "cannot create uploads dir: " + ioe.getMessage()));
            }
            // 工作区相对路径,doc.normalize / file.read 用的就是这个
            String relativePath = root.relativize(target).toString().replace('\\', '/');

            return filePart.transferTo(target)
                    .then(Mono.fromCallable(() -> {
                        long size = Files.size(target);
                        if (size > MAX_FILE_BYTES) {
                            try { Files.deleteIfExists(target); } catch (Exception ignored) { /* best-effort */ }
                            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                                    "file " + originalName + " exceeds " + (MAX_FILE_BYTES / 1024 / 1024) + "MB limit");
                        }
                        Map<String, Object> resp = new LinkedHashMap<>();
                        resp.put("name", originalName);
                        resp.put("storedName", storedName);
                        resp.put("path", relativePath);
                        resp.put("contentType", filePart.headers().getContentType() == null
                                ? "application/octet-stream"
                                : filePart.headers().getContentType().toString());
                        resp.put("size", size);
                        resp.put("sessionId", session);
                        resp.put("agentId", hostAgent);
                        log.info("chat.attachment.uploaded userId={}, session={}, file={}, size={}, path={}",
                                userId, session, originalName, size, relativePath);
                        return resp;
                    }).subscribeOn(Schedulers.boundedElastic()));
        });
    }

    /** 防路径穿越:剥掉用户名里的 .. / / \ 等,留下安全的文件名片段。 */
    private static String sanitizeFilename(String raw) {
        if (raw == null || raw.isBlank()) return "unnamed";
        String name = raw.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\x00-\\x1f]", "");
        name = name.replace("..", "_");
        if (name.length() > 200) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 && dot > name.length() - 16 ? name.substring(dot) : "";
            name = name.substring(0, 200 - ext.length()) + ext;
        }
        return name;
    }

    /** sessionId 也防一下穿越,只允许 [a-zA-Z0-9_-]。 */
    private static String sessionRelpath(String sessionId) {
        return sessionId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}

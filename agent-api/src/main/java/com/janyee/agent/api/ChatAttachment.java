package com.janyee.agent.api;

/**
 * Chat 请求里携带的附件描述。两种用法:
 *   - 小文件(老路径):前端把文件 base64 编码塞 content,path/sizeBytes 留 null
 *   - 大文件(新路径):前端先 POST /api/chat/attachments 流式上传 → 服务端返工作区相对路径,
 *     这里只带 name + path + contentType + sizeBytes,content 留 null。
 *     后端 ChatController 看到 path 非空就把它作为路径引用传下去,不会复制内容。
 */
public record ChatAttachment(
        String name,
        String contentType,
        String content,
        String path,
        Long sizeBytes
) {
    /** 老调用点兼容:只有 3 个字段时,path / sizeBytes 自动补 null。 */
    public ChatAttachment(String name, String contentType, String content) {
        this(name, contentType, content, null, null);
    }
}

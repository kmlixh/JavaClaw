package com.janyee.agent.domain;

/**
 * 附件可以两种来源:
 *   1. 老路径(< 1MB,base64 内联):content 非空,path 为空。直接塞 JSON 走 chat 请求。
 *   2. 新路径(&gt;= 1MB / 大文档):path 非空,content 为空。
 *      前端先 POST /api/chat/attachments 上传 → 文件流式落到工作区 uploads/&lt;sessionId&gt;/ 下,
 *      返回相对路径;chat 请求里只带 name+path 引用。
 *      doc.normalize / file.read 工具可以直接用 path 找到这个文件。
 */
public record ChatAttachment(
        String name,
        String contentType,
        String content,
        String path,
        Long sizeBytes
) {
    /** 老调用点兼容:只传 name/contentType/content,path/sizeBytes 留空。 */
    public ChatAttachment(String name, String contentType, String content) {
        this(name, contentType, content, null, null);
    }
}

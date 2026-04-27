-- 把用户发的 @mention references 和 附件(空间区域 / 图片 / 文件) 一并落进 session_message,
-- 这样刷新页面 / 打开历史会话后点"重新发送"也能恢复完整 payload,而不是只复用 message 文本。
-- 两列都用 TEXT,内容是 JSON 数组字符串;旧消息这两列为 NULL,前端/后端读取时按空列表处理。
ALTER TABLE session_message
    ADD COLUMN IF NOT EXISTS references_json TEXT,
    ADD COLUMN IF NOT EXISTS attachments_json TEXT;

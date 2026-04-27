package com.janyee.agent.web.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 artifact 下载的 Content-Disposition 头：
 *   - 中文名必须走 RFC 5987 的 filename*=UTF-8'' 形式，否则浏览器把汉字替换成下划线；
 *   - ASCII fallback filename="..." 必须依然存在，而且只含安全字符；
 *   - 特殊字符（引号、控制字符）要被清洗成下划线避免把头本身搞坏。
 */
class QueryControllerContentDispositionTest {

    @Test
    void chineseFileNameUsesRfc5987Encoding() {
        String header = QueryController.buildContentDisposition("盘龙区覆盖分析综合报告.md");
        // 中文 URL 编码后的片段，百分号 + 三位十六进制大写
        assertTrue(header.contains("filename*=UTF-8''"),
                "必须出现 RFC 5987 的 filename*= 字段: " + header);
        assertTrue(header.contains("%E7%9B%98%E9%BE%99%E5%8C%BA"),
                "盘龙区三个汉字应被 UTF-8 百分号编码: " + header);
        assertTrue(header.endsWith(".md"),
                "扩展名应原样保留在编码末尾: " + header);
        // 老浏览器看的 filename="..." 里不应该漏出未转义的汉字字节
        int fallbackStart = header.indexOf("filename=\"");
        int fallbackEnd = header.indexOf("\";", fallbackStart);
        String fallback = header.substring(fallbackStart + 10, fallbackEnd);
        for (char c : fallback.toCharArray()) {
            assertTrue(c < 0x80, "ASCII fallback 含非 ASCII 字符: " + (int) c + " in " + fallback);
        }
    }

    @Test
    void asciiOnlyNameStaysInFallback() {
        String header = QueryController.buildContentDisposition("coverage-report.md");
        assertTrue(header.contains("filename=\"coverage-report.md\""),
                "纯 ASCII 名字 fallback 应原样保留: " + header);
        // filename* 也应同步，但没有需要编码的字节
        assertTrue(header.contains("filename*=UTF-8''coverage-report.md"),
                "filename* 对纯 ASCII 也应同步给出: " + header);
    }

    @Test
    void spacesAndParensGetPercentEncoded() {
        String header = QueryController.buildContentDisposition("report v2 (final).md");
        // URLEncoder 会把空格编成 + —— 代码里已把 + 再替换成 %20
        assertTrue(header.contains("%20"),
                "空格必须编码为 %20 而不是 +（+ 在 filename* 里语义不对）: " + header);
        assertFalse(header.contains("''report+"),
                "filename* 里不应出现 + 形式的空格: " + header);
    }

    @Test
    void dangerousCharactersAreSanitizedInAsciiFallback() {
        // 引号和控制字符会把整个 header 搞坏 —— ASCII fallback 必须清洗
        String header = QueryController.buildContentDisposition("na\"me\n.md");
        int fallbackStart = header.indexOf("filename=\"");
        int fallbackEnd = header.indexOf("\";", fallbackStart);
        String fallback = header.substring(fallbackStart + 10, fallbackEnd);
        assertFalse(fallback.contains("\""),
                "ASCII fallback 不允许有裸引号: " + fallback);
        assertFalse(fallback.contains("\n"),
                "ASCII fallback 不允许有换行: " + fallback);
    }

    @Test
    void blankNameFallsBackToDefault() {
        String header = QueryController.buildContentDisposition("");
        assertEquals("attachment; filename=\"artifact\"; filename*=UTF-8''",
                header.substring(0, header.indexOf("''") + 2),
                "空名字 fallback 应走默认 'artifact'，不该抛空串: " + header);
    }
}

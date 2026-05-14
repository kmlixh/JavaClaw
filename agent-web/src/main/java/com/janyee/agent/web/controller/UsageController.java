package com.janyee.agent.web.controller;

import com.janyee.agent.runtime.usage.UsageRecentRow;
import com.janyee.agent.runtime.usage.UsageSummaryRow;
import com.janyee.agent.runtime.usage.UsageTimeseriesRow;
import com.janyee.agent.infra.usage.LlmUsageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Token 用量统计 REST 端点。所有 endpoint 共享 {@link LlmUsageService} 内部的
 * SessionVisibility 权限过滤(管理员看全 / 租户管理员看本租户 / 普通用户看自己),
 * 不在这一层做重复 guard。
 *
 * <p>时间参数全部按 ISO-8601 字符串(支持 epoch-millis 兜底);省略时 from=now-7d, to=now。</p>
 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final LlmUsageService usageService;

    public UsageController(LlmUsageService usageService) {
        this.usageService = usageService;
    }

    @GetMapping("/summary")
    public List<UsageSummaryRow> summary(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "groupBy", required = false, defaultValue = "none") String groupBy
    ) {
        Instant fromTs = parseInstant(from, Instant.now().minus(7, ChronoUnit.DAYS));
        Instant toTs = parseInstant(to, Instant.now());
        return usageService.summary(fromTs, toTs, groupBy);
    }

    @GetMapping("/timeseries")
    public List<UsageTimeseriesRow> timeseries(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "interval", required = false, defaultValue = "day") String interval,
            @RequestParam(value = "groupBy", required = false, defaultValue = "none") String groupBy
    ) {
        Instant fromTs = parseInstant(from, Instant.now().minus(7, ChronoUnit.DAYS));
        Instant toTs = parseInstant(to, Instant.now());
        return usageService.timeseries(fromTs, toTs, interval, groupBy);
    }

    @GetMapping("/recent")
    public List<UsageRecentRow> recent(@RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        return usageService.recent(limit);
    }

    /** 接受 ISO-8601 (2026-05-09T00:00:00Z) 或 epoch millis 字符串;空/解析失败用 fallback。 */
    private Instant parseInstant(String raw, Instant fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String trimmed = raw.trim();
        try {
            return Instant.parse(trimmed);
        } catch (Exception ignore) {
            // 兜底:纯数字当 epoch ms,允许前端简化处理
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(trimmed));
        } catch (Exception ignore) {
            return fallback;
        }
    }
}

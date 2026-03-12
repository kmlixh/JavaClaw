package com.janyee.agent.runtime.loop;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SimpleTextToolCallDetector implements ToolCallDetector {

    private static final Pattern TOOL_PATTERN = Pattern.compile("(?s)TOOL_CALL\\s*:\\s*(\\w[\\w.\\-]*)\\s*\\nARGS\\s*:\\s*(\\{.*})");

    @Override
    public Optional<ToolCallRequest> detect(ModelTurnResult result) {
        Matcher matcher = TOOL_PATTERN.matcher(result.fullText());
        if (!matcher.find()) {
            return Optional.empty();
        }

        return Optional.of(new ToolCallRequest(
                UUID.randomUUID().toString(),
                matcher.group(1),
                matcher.group(2),
                matcher.group(0)
        ));
    }
}

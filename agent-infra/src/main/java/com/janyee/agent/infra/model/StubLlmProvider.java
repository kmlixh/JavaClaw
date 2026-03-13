package com.janyee.agent.infra.model;

import com.janyee.agent.runtime.model.LlmChatRequest;
import com.janyee.agent.runtime.model.LlmStreamEvent;
import reactor.core.publisher.Flux;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StubLlmProvider {

    private static final Pattern MESSAGE_PATTERN = Pattern.compile("(?m)^Message:\\s*(.+)$");
    private static final Pattern TOOL_RESULT_SUMMARY_PATTERN = Pattern.compile("(?im)^summary:\\s*(.+)$");

    private StubLlmProvider() {
    }

    static Flux<LlmStreamEvent> response(LlmChatRequest request) {
        String prompt = request.prompt();
        if (prompt.contains("Tool result:") || prompt.contains("Tool execution result:")) {
            return Flux.just(
                    new LlmStreamEvent("token", "Tool loop completed. " + extractToolSummary(prompt)),
                    new LlmStreamEvent("finish", "completed")
            );
        }

        String message = extractMessage(prompt);
        if (message.startsWith("/tool echo ")) {
            String text = message.substring("/tool echo ".length()).trim();
            return Flux.just(
                    new LlmStreamEvent("tool_call_request", """
                            {"id":"stub-tool-call","name":"echo","arguments":"{\\"text\\":\\"%s\\"}"}""".formatted(escapeJson(text))),
                    new LlmStreamEvent("finish", "tool_call")
            );
        }

        String response = "Phase 1 stub response for prompt: " + request.prompt();
        return Flux.just(new LlmStreamEvent("token", response), new LlmStreamEvent("finish", "completed"));
    }

    private static String extractMessage(String prompt) {
        Matcher matcher = MESSAGE_PATTERN.matcher(prompt);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String extractToolSummary(String prompt) {
        Matcher matcher = TOOL_RESULT_SUMMARY_PATTERN.matcher(prompt);
        if (matcher.find()) {
            return "Last tool summary: " + matcher.group(1).trim();
        }
        return "Tool returned without summary.";
    }

    private static String escapeJson(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}

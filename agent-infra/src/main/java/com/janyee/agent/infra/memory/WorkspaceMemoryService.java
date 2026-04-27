package com.janyee.agent.infra.memory;

import com.janyee.agent.infra.persistence.entity.MemoryNoteEntity;
import com.janyee.agent.infra.persistence.repository.MemoryNoteRepository;
import com.janyee.agent.memory.MemoryItem;
import com.janyee.agent.memory.MemoryQuery;
import com.janyee.agent.memory.MemoryService;
import com.janyee.agent.workspace.WorkspaceKnowledgeFile;
import com.janyee.agent.workspace.WorkspaceService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@Primary
public class WorkspaceMemoryService implements MemoryService {

    private final WorkspaceService workspaceService;
    private final MemoryNoteRepository memoryNoteRepository;

    public WorkspaceMemoryService(WorkspaceService workspaceService, MemoryNoteRepository memoryNoteRepository) {
        this.workspaceService = workspaceService;
        this.memoryNoteRepository = memoryNoteRepository;
    }

    @Override
    public List<MemoryItem> retrieve(MemoryQuery query) {
        List<Candidate> candidates = new ArrayList<>();
        // MEMORY.md / knowledge/* 是用户显式维护的 agent 级资源，跨 session 共享是合理的 ——
        // 这些文件不会被 agent 自动写入。
        workspaceService.readAgentFile(query.agentId(), "MEMORY.md")
                .ifPresent(content -> candidates.addAll(extractCandidates("memory", content)));
        for (WorkspaceKnowledgeFile file : workspaceService.listKnowledgeFiles(query.agentId())) {
            candidates.addAll(extractCandidates(file.relativePath(), file.content()));
        }

        // 按 scope 检索（V14 之后的权威规则）：
        //   - 本 session 的 session-scope note（通常是 run_summary）—— 只本 session 可见
        //   - 全部 agent-scope / global-scope note —— 跨 session 共享
        // 旧的 source='pinned' 兼容也保留：历史数据在 V14 迁移后已被重标为 scope='agent'，
        //   但手工写入的代码路径还有可能用 source=pinned 作标签，这里按 source 再兜一层。
        if (query.sessionId() != null && !query.sessionId().isBlank()) {
            memoryNoteRepository.findTop20ByAgentIdAndSessionIdOrderByCreatedAtDesc(
                            query.agentId(), query.sessionId())
                    .stream()
                    .filter(note -> "session".equals(safeScope(note.getScope())))
                    .forEach(note -> candidates.add(
                            new Candidate("db-note#" + note.getId() + "/session", note.getContent())));
        }
        memoryNoteRepository.findTop40ByAgentIdAndScopeInOrderByCreatedAtDesc(
                        query.agentId(), java.util.List.of("agent", "global"))
                .forEach(note -> candidates.add(
                        new Candidate("db-note#" + note.getId() + "/" + safeScope(note.getScope()),
                                note.getContent())));

        Set<String> terms = extractTerms(query.query());
        return candidates.stream()
                .map(candidate -> new ScoredCandidate(candidate, score(candidate.content(), terms)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredCandidate::score).reversed())
                .limit(5)
                .map(scored -> new MemoryItem(scored.candidate().id(), truncate(scored.candidate().content(), 400)))
                .toList();
    }

    @Override
    public void saveNote(String agentId, String sessionId, String runId, String content, String source) {
        if (content == null || content.isBlank()) {
            return;
        }
        MemoryNoteEntity entity = new MemoryNoteEntity();
        entity.setAgentId(agentId);
        entity.setSessionId(sessionId);
        entity.setRunId(runId);
        entity.setSource(source == null || source.isBlank() ? "runtime" : source);
        entity.setContent(truncate(content.trim(), 2000));
        memoryNoteRepository.save(entity);
    }

    private List<Candidate> extractCandidates(String source, String content) {
        List<String> blocks = splitBlocks(content);
        List<Candidate> result = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) {
            String block = blocks.get(index).trim();
            if (!block.isBlank()) {
                result.add(new Candidate(source + "#" + (index + 1), block));
            }
        }
        return result;
    }

    private List<String> splitBlocks(String content) {
        String normalized = content.replace("\r\n", "\n");
        if (normalized.contains("\n\n")) {
            return List.of(normalized.split("\\n\\n+"));
        }
        return normalized.lines().toList();
    }

    private Set<String> extractTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        for (String token : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}_-]+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        return terms;
    }

    private int score(String content, Set<String> terms) {
        if (terms.isEmpty()) {
            return 0;
        }
        String normalized = content.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (normalized.contains(term)) {
                score++;
            }
        }
        return score;
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private static String safeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "session";
        }
        return scope;
    }

    private record Candidate(String id, String content) {
    }

    private record ScoredCandidate(Candidate candidate, int score) {
    }
}

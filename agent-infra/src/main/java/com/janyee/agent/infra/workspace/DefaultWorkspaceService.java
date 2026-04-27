package com.janyee.agent.infra.workspace;

import com.janyee.agent.infra.config.AgentPlatformProperties;
import com.janyee.agent.infra.prompt.BuiltinDocumentWorkflowCatalog;
import com.janyee.agent.infra.persistence.repository.KnowledgeEntryRepository;
import com.janyee.agent.infra.persistence.repository.ToolDefinitionRepository;
import com.janyee.agent.workspace.WorkspaceService;
import com.janyee.agent.workspace.WorkspaceKnowledgeFile;
import com.janyee.agent.workspace.WorkspaceToolPolicy;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class DefaultWorkspaceService implements WorkspaceService {

    private final AgentPlatformProperties properties;
    private final KnowledgeEntryRepository knowledgeEntryRepository;
    private final ToolDefinitionRepository toolDefinitionRepository;
    private final Yaml yaml;

    public DefaultWorkspaceService(
            AgentPlatformProperties properties,
            KnowledgeEntryRepository knowledgeEntryRepository,
            ToolDefinitionRepository toolDefinitionRepository
    ) {
        this.properties = properties;
        this.knowledgeEntryRepository = knowledgeEntryRepository;
        this.toolDefinitionRepository = toolDefinitionRepository;
        this.yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    @Override
    public List<String> listAgentIds() {
        Path root = Path.of(properties.workspaceRoot()).normalize();
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(root)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException error) {
            throw new IllegalStateException("failed to list workspace agents", error);
        }
    }

    @Override
    public Path getWorkspaceRoot(String agentId) {
        return Path.of(properties.workspaceRoot()).resolve(agentId).normalize();
    }

    @Override
    public Optional<String> readAgentFile(String agentId, String fileName) {
        Path workspaceRoot = getWorkspaceRoot(agentId);
        Path target = workspaceRoot.resolve(fileName).normalize();
        if (!target.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("workspace path escape detected: " + fileName);
        }
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(target, StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new IllegalStateException("failed to read workspace file: " + target, error);
        }
    }

    @Override
    public List<WorkspaceKnowledgeFile> listKnowledgeFiles(String agentId) {
        // Plan A Phase 3: DB 是 knowledge 的唯一真相源；workspace 文件只做为 fallback，只在
        // DB 里没有同名条目时才挤进 prompt，这样避免"两份同名知识并列渲染让 LLM 分不清用哪份"。
        //
        // 输出的 relativePath 用 "db/<title>" / "builtin/<path>" / "workspace/<relative>" 前缀
        // 明示来源 —— prompt 里 LLM 能看出来这份知识的出处，排查起来方便。

        List<WorkspaceKnowledgeFile> builtinFiles = BuiltinDocumentWorkflowCatalog.knowledgeFiles().stream()
                .map(f -> new WorkspaceKnowledgeFile("builtin/" + f.relativePath(), f.content()))
                .toList();

        List<WorkspaceKnowledgeFile> databaseFiles = knowledgeEntryRepository
                .findByAgentIdAndEnabledTrueOrderByUpdatedAtDesc(agentId).stream()
                .limit(10)
                .map(entity -> new WorkspaceKnowledgeFile(
                        "db/" + entity.getTitle(),
                        entity.getContent()
                ))
                .toList();

        // 收集 DB 里的 title（小写），用于对 workspace 文件做去重
        java.util.Set<String> dbTitles = databaseFiles.stream()
                .map(f -> f.relativePath().substring("db/".length()).toLowerCase(Locale.ROOT).trim())
                .collect(java.util.stream.Collectors.toSet());

        List<WorkspaceKnowledgeFile> result = new java.util.ArrayList<>(builtinFiles);
        result.addAll(databaseFiles);

        Path knowledgeRoot = getWorkspaceRoot(agentId).resolve("knowledge").normalize();
        if (Files.exists(knowledgeRoot) && Files.isDirectory(knowledgeRoot)) {
            try (Stream<Path> stream = Files.walk(knowledgeRoot, 2)) {
                List<WorkspaceKnowledgeFile> fileBased = stream
                        .filter(Files::isRegularFile)
                        .sorted(Comparator.naturalOrder())
                        .limit(10)
                        .map(path -> {
                            String rel = knowledgeRoot.relativize(path).toString().replace('\\', '/');
                            return new WorkspaceKnowledgeFile("workspace/" + rel, readFile(path));
                        })
                        // 去重：文件名与 DB title 匹配就跳过（DB 优先）
                        .filter(f -> {
                            String rel = f.relativePath().substring("workspace/".length());
                            String basename = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
                            if (basename.endsWith(".md")) basename = basename.substring(0, basename.length() - 3);
                            return !dbTitles.contains(basename.toLowerCase(Locale.ROOT).trim());
                        })
                        .toList();
                result.addAll(fileBased);
            } catch (IOException error) {
                throw new IllegalStateException("failed to list knowledge files for agent: " + agentId, error);
            }
        }
        return result.stream().limit(20).toList();
    }

    @Override
    public WorkspaceToolPolicy getToolPolicy(String agentId) {
        List<String> databaseTools = toolDefinitionRepository.findByAgentIdAndEnabledTrueOrderByUpdatedAtDesc(agentId).stream()
                .map(entity -> WorkspaceToolPolicy.normalize(entity.getToolName()))
                .filter(name -> !name.isBlank())
                .distinct()
                .toList();
        if (!databaseTools.isEmpty()) {
            return new WorkspaceToolPolicy(
                    WorkspaceToolPolicy.Mode.ALLOW_LIST,
                    mergeBuiltinAllow(databaseTools, Set.of()),
                    Set.of()
            );
        }
        String content = readAgentFile(agentId, "TOOLS.yaml").orElse(null);
        if (content == null || content.isBlank()) {
            return WorkspaceToolPolicy.allowAll();
        }

        Object loaded = yaml.load(content);
        if (!(loaded instanceof Map<?, ?> root)) {
            return WorkspaceToolPolicy.allowAll();
        }

        Map<?, ?> policyRoot = root.containsKey("tools") && root.get("tools") instanceof Map<?, ?> nested
                ? nested
                : root;
        WorkspaceToolPolicy.Mode mode = parseMode(policyRoot.get("mode"));
        Set<String> allow = readToolNames(policyRoot.get("allow"));
        Set<String> deny = readToolNames(policyRoot.get("deny"));
        if (mode == WorkspaceToolPolicy.Mode.ALLOW_LIST) {
            allow = mergeBuiltinAllow(allow, deny);
        }
        return new WorkspaceToolPolicy(mode, allow, deny);
    }

    private Set<String> mergeBuiltinAllow(Collection<String> explicitAllow, Set<String> deny) {
        Set<String> merged = new LinkedHashSet<>(explicitAllow);
        for (String builtin : BuiltinDocumentWorkflowCatalog.builtinToolNames()) {
            String normalized = WorkspaceToolPolicy.normalize(builtin);
            if (!normalized.isBlank() && !deny.contains(normalized)) {
                merged.add(normalized);
            }
        }
        return Set.copyOf(merged);
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("failed to read file: " + path, error);
        }
    }

    private WorkspaceToolPolicy.Mode parseMode(Object rawMode) {
        if (rawMode == null) {
            return WorkspaceToolPolicy.Mode.ALLOW_ALL;
        }
        String mode = rawMode.toString().trim().toLowerCase(Locale.ROOT);
        if ("allow-list".equals(mode) || "allow_list".equals(mode) || "whitelist".equals(mode)) {
            return WorkspaceToolPolicy.Mode.ALLOW_LIST;
        }
        return WorkspaceToolPolicy.Mode.ALLOW_ALL;
    }

    private Set<String> readToolNames(Object value) {
        if (!(value instanceof Collection<?> items)) {
            return Set.of();
        }
        return items.stream()
                .map(Object::toString)
                .map(WorkspaceToolPolicy::normalize)
                .filter(name -> !name.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}

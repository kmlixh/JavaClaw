package com.janyee.agent.infra.workspace;

import com.janyee.agent.infra.config.AgentPlatformProperties;
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
        List<WorkspaceKnowledgeFile> databaseFiles = knowledgeEntryRepository.findByAgentIdAndEnabledTrueOrderByUpdatedAtDesc(agentId).stream()
                .limit(10)
                .map(entity -> new WorkspaceKnowledgeFile(
                        "db/" + entity.getTitle(),
                        entity.getContent()
                ))
                .toList();
        Path knowledgeRoot = getWorkspaceRoot(agentId).resolve("knowledge").normalize();
        if (!Files.exists(knowledgeRoot) || !Files.isDirectory(knowledgeRoot)) {
            return databaseFiles;
        }

        try (Stream<Path> stream = Files.walk(knowledgeRoot, 2)) {
            List<WorkspaceKnowledgeFile> fileBased = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .limit(10)
                    .map(path -> new WorkspaceKnowledgeFile(
                            knowledgeRoot.relativize(path).toString().replace('\\', '/'),
                            readFile(path)
                    ))
                    .toList();
            return Stream.concat(databaseFiles.stream(), fileBased.stream())
                    .limit(20)
                    .toList();
        } catch (IOException error) {
            throw new IllegalStateException("failed to list knowledge files for agent: " + agentId, error);
        }
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
                    Set.copyOf(databaseTools),
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
        return new WorkspaceToolPolicy(mode, allow, deny);
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

package com.janyee.agent.infra.tool;

import com.janyee.agent.workspace.WorkspaceService;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

final class WorkspacePathSupport {

    private WorkspacePathSupport() {
    }

    /**
     * 把 LLM 传来的路径归一化到 workspace 内。常见的 LLM 错误会被自动修正：
     * <ul>
     *   <li>{@code E:\...\workspaces\ops-agent\...} 这种**绝对路径**恰好落在 workspace root
     *       之内 → 允许，归一化后保留；</li>
     *   <li>{@code workspaces/ops-agent/knowledge} 这种**带 workspaces/&lt;agent&gt;/ 前缀**的
     *       相对路径 → 自动剥掉前缀，解析成 workspace-relative；</li>
     *   <li>真正越界（例如 {@code ../../etc/passwd}）→ 仍然抛 escape 错误。</li>
     * </ul>
     *
     * <p>规范化后必须用 {@code toAbsolutePath().normalize()} 对比才稳妥，否则在 Windows 上
     * 相对 root 和绝对 target 的 {@code startsWith} 会假性失败。</p>
     */
    static Path resolvePath(WorkspaceService workspaceService, String agentId, String relativePath) {
        Path root = workspaceService.getWorkspaceRoot(agentId).toAbsolutePath().normalize();
        String value = relativePath == null || relativePath.isBlank() ? "." : relativePath;

        // 自动剥掉 "workspaces/<agentId>/" 或 "workspaces\\<agentId>\\" 前缀 —— LLM 看到
        // workspace.path 返回的绝对路径后，经常把 workspaces/ops-agent/xxx 当成相对路径传。
        String agentPrefixForward = "workspaces/" + agentId + "/";
        String agentPrefixBackward = "workspaces\\" + agentId + "\\";
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith(agentPrefixForward)) {
            value = normalized.substring(agentPrefixForward.length());
        } else if (value.startsWith(agentPrefixBackward)) {
            value = value.substring(agentPrefixBackward.length());
        } else if (normalized.equals("workspaces/" + agentId)) {
            value = ".";
        }

        Path candidate = parsePath(value);
        Path target = candidate != null && candidate.isAbsolute()
                ? candidate.toAbsolutePath().normalize()
                : root.resolve(value).toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException(
                    "workspace path escape detected: '" + relativePath
                            + "' (workspace root is '" + root + "'; use a path inside this root or '.').");
        }
        return target;
    }

    private static Path parsePath(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException ignored) {
            return null;
        }
    }
}

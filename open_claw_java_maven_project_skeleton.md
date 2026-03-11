# A：Maven 多模块 Java 项目骨架

下面这份内容是可直接复制到本地的项目骨架说明与关键文件。

---

## 1）目录结构

```text
ai-agent-platform/
├─ pom.xml
├─ README.md
├─ agent-app/
│  ├─ pom.xml
│  └─ src/main/
│     ├─ java/com/janyee/agent/app/AgentApplication.java
│     └─ resources/application.yml
├─ agent-api/
│  ├─ pom.xml
│  └─ src/main/java/com/janyee/agent/api/
│     ├─ ChatSendRequest.java
│     └─ ChatSendResponse.java
├─ agent-domain/
│  ├─ pom.xml
│  └─ src/main/java/com/janyee/agent/domain/
│     ├─ AgentBinding.java
│     ├─ AgentDefinition.java
│     ├─ AgentEvent.java
│     ├─ AgentEventType.java
│     ├─ IncomingMessage.java
│     ├─ PromptContext.java
│     ├─ RunRequest.java
│     ├─ RunStatus.java
│     ├─ ToolInvocation.java
│     ├─ ToolResult.java
│     ├─ ToolRiskLevel.java
│     └─ ToolSchema.java
├─ agent-runtime/
│  ├─ pom.xml
│  └─ src/main/java/com/janyee/agent/runtime/
│     ├─ AgentRunner.java
│     ├─ loop/SimpleAgentRunner.java
│     ├─ model/LlmChatRequest.java
│     ├─ model/LlmProvider.java
│     ├─ model/LlmStreamEvent.java
│     └─ prompt/PromptAssembler.java
├─ agent-tool/
│  ├─ pom.xml
│  └─ src/main/java/com/janyee/agent/tool/
│     ├─ AgentTool.java
│     ├─ builtin/EchoTool.java
│     └─ policy/ToolPolicyService.java
├─ agent-memory/
│  ├─ pom.xml
│  └─ src/main/java/com/janyee/agent/memory/
│     ├─ MemoryItem.java
│     ├─ MemoryQuery.java
│     └─ MemoryService.java
├─ agent-workspace/
│  ├─ pom.xml
│  └─ src/main/java/com/janyee/agent/workspace/WorkspaceService.java
├─ agent-security/
│  ├─ pom.xml
│  └─ src/main/java/com/janyee/agent/security/
│     ├─ ApprovalDecision.java
│     └─ ApprovalService.java
├─ agent-channel/
│  ├─ pom.xml
│  └─ src/main/java/com/janyee/agent/channel/
│     ├─ web/WebChannelAdapter.java
│     └─ webhook/WebhookChannelAdapter.java
├─ agent-web/
│  ├─ pom.xml
│  └─ src/main/java/com/janyee/agent/web/controller/ChatController.java
└─ agent-infra/
   └─ pom.xml
```

---

## 2）父 POM：`pom.xml`

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.janyee</groupId>
    <artifactId>ai-agent-platform</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <packaging>pom</packaging>

    <name>ai-agent-platform</name>
    <description>OpenClaw-like Java Agent Platform</description>

    <modules>
        <module>agent-app</module>
        <module>agent-api</module>
        <module>agent-domain</module>
        <module>agent-infra</module>
        <module>agent-runtime</module>
        <module>agent-tool</module>
        <module>agent-memory</module>
        <module>agent-workspace</module>
        <module>agent-security</module>
        <module>agent-channel</module>
        <module>agent-web</module>
    </modules>

    <properties>
        <java.version>21</java.version>
        <spring.boot.version>3.3.2</spring.boot.version>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring.boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.11.0</version>
                    <configuration>
                        <source>${maven.compiler.source}</source>
                        <target>${maven.compiler.target}</target>
                    </configuration>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

---

## 3）启动模块：`agent-app/pom.xml`

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <parent>
        <groupId>com.janyee</groupId>
        <artifactId>ai-agent-platform</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <modelVersion>4.0.0</modelVersion>
    <artifactId>agent-app</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.janyee</groupId>
            <artifactId>agent-web</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.janyee</groupId>
            <artifactId>agent-runtime</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.janyee</groupId>
            <artifactId>agent-tool</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.janyee</groupId>
            <artifactId>agent-memory</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.janyee</groupId>
            <artifactId>agent-workspace</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.janyee</groupId>
            <artifactId>agent-security</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <version>${spring.boot.version}</version>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## 4）启动类：`agent-app/src/main/java/com/janyee/agent/app/AgentApplication.java`

```java
package com.janyee.agent.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.janyee.agent")
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
```

---

## 5）配置文件：`agent-app/src/main/resources/application.yml`

```yaml
server:
  port: 8080

spring:
  application:
    name: ai-agent-platform
  main:
    web-application-type: reactive

agent:
  workspace-root: ./workspaces
  default-agent-id: dev-agent
  runtime:
    max-tool-iterations: 5
    stream-buffer-size: 256

logging:
  level:
    root: INFO
    com.janyee.agent: INFO
```

---

## 6）API 模型：`agent-api`

### `ChatSendRequest.java`

```java
package com.janyee.agent.api;

public record ChatSendRequest(
        String sessionId,
        String agentId,
        String userId,
        String message
) {
}
```

### `ChatSendResponse.java`

```java
package com.janyee.agent.api;

public record ChatSendResponse(
        String sessionId,
        String status
) {
}
```

---

## 7）领域模型：`agent-domain`

### `AgentEventType.java`

```java
package com.janyee.agent.domain;

public enum AgentEventType {
    RUN_STARTED,
    RUN_STATUS,
    TOKEN_DELTA,
    TOOL_REQUESTED,
    TOOL_STARTED,
    TOOL_COMPLETED,
    RUN_COMPLETED,
    RUN_FAILED
}
```

### `RunStatus.java`

```java
package com.janyee.agent.domain;

public enum RunStatus {
    RECEIVED,
    CONTEXT_BUILT,
    MODEL_RUNNING,
    TOOL_REQUESTED,
    TOOL_EXECUTING,
    TOOL_RESULT_APPENDED,
    COMPLETED,
    FAILED,
    WAITING_APPROVAL
}
```

### `ToolRiskLevel.java`

```java
package com.janyee.agent.domain;

public enum ToolRiskLevel {
    SAFE,
    GUARDED,
    PRIVILEGED,
    APPROVAL_REQUIRED
}
```

### `AgentEvent.java`

```java
package com.janyee.agent.domain;

public record AgentEvent(
        AgentEventType type,
        String sessionId,
        String runId,
        String content
) {
}
```

### `AgentDefinition.java`

```java
package com.janyee.agent.domain;

public record AgentDefinition(
        String id,
        String displayName,
        String systemPrompt,
        String workspacePath
) {
}
```

### `IncomingMessage.java`

```java
package com.janyee.agent.domain;

public record IncomingMessage(
        String channel,
        String userId,
        String sessionId,
        String content
) {
}
```

### `AgentBinding.java`

```java
package com.janyee.agent.domain;

public record AgentBinding(
        String agentId
) {
}
```

### `RunRequest.java`

```java
package com.janyee.agent.domain;

public record RunRequest(
        String sessionId,
        String agentId,
        String userId,
        String message
) {
}
```

### `ToolSchema.java`

```java
package com.janyee.agent.domain;

public record ToolSchema(
        String name,
        String description,
        String jsonSchema
) {
}
```

### `ToolInvocation.java`

```java
package com.janyee.agent.domain;

public record ToolInvocation(
        String sessionId,
        String toolName,
        String argumentsJson
) {
}
```

### `ToolResult.java`

```java
package com.janyee.agent.domain;

public record ToolResult(
        boolean ok,
        String summary,
        String dataJson,
        String artifactsJson,
        String error
) {
}
```

### `PromptContext.java`

```java
package com.janyee.agent.domain;

public record PromptContext(
        String systemPrompt,
        String assembledPrompt
) {
}
```

---

## 8）Runtime 核心：`agent-runtime`

### `AgentRunner.java`

```java
package com.janyee.agent.runtime;

import com.janyee.agent.domain.AgentEvent;
import com.janyee.agent.domain.RunRequest;
import reactor.core.publisher.Flux;

public interface AgentRunner {
    Flux<AgentEvent> run(RunRequest request);
}
```

### `PromptAssembler.java`

```java
package com.janyee.agent.runtime.prompt;

import com.janyee.agent.domain.PromptContext;
import com.janyee.agent.domain.RunRequest;

public interface PromptAssembler {
    PromptContext assemble(RunRequest request);
}
```

### `LlmChatRequest.java`

```java
package com.janyee.agent.runtime.model;

public record LlmChatRequest(
        String model,
        String prompt
) {
}
```

### `LlmStreamEvent.java`

```java
package com.janyee.agent.runtime.model;

public record LlmStreamEvent(
        String type,
        String content
) {
}
```

### `LlmProvider.java`

```java
package com.janyee.agent.runtime.model;

import reactor.core.publisher.Flux;

public interface LlmProvider {
    Flux<LlmStreamEvent> chatStream(LlmChatRequest request);
}
```

### `SimpleAgentRunner.java`

```java
package com.janyee.agent.runtime.loop;

import com.janyee.agent.domain.AgentEvent;
import com.janyee.agent.domain.AgentEventType;
import com.janyee.agent.domain.RunRequest;
import com.janyee.agent.runtime.AgentRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.UUID;

@Component
public class SimpleAgentRunner implements AgentRunner {

    @Override
    public Flux<AgentEvent> run(RunRequest request) {
        String runId = UUID.randomUUID().toString();

        return Flux.just(
                new AgentEvent(AgentEventType.RUN_STARTED, request.sessionId(), runId, "run started"),
                new AgentEvent(AgentEventType.RUN_STATUS, request.sessionId(), runId, "context built"),
                new AgentEvent(AgentEventType.RUN_STATUS, request.sessionId(), runId, "model running"),
                new AgentEvent(AgentEventType.TOKEN_DELTA, request.sessionId(), runId, "Hello, "),
                new AgentEvent(AgentEventType.TOKEN_DELTA, request.sessionId(), runId, "this is a Java agent skeleton."),
                new AgentEvent(AgentEventType.RUN_COMPLETED, request.sessionId(), runId, "completed")
        );
    }
}
```

---

## 9）Tool 模块：`agent-tool`

### `AgentTool.java`

```java
package com.janyee.agent.tool;

import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;

public interface AgentTool {
    String name();
    ToolSchema schema();
    ToolResult execute(ToolInvocation invocation);
}
```

### `ToolPolicyService.java`

```java
package com.janyee.agent.tool.policy;

public interface ToolPolicyService {
    boolean isAllowed(String agentId, String toolName);
}
```

### `EchoTool.java`

```java
package com.janyee.agent.tool.builtin;

import com.janyee.agent.domain.ToolInvocation;
import com.janyee.agent.domain.ToolResult;
import com.janyee.agent.domain.ToolSchema;
import com.janyee.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

@Component
public class EchoTool implements AgentTool {

    @Override
    public String name() {
        return "echo";
    }

    @Override
    public ToolSchema schema() {
        return new ToolSchema(
                "echo",
                "Echo input arguments",
                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}"
        );
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        return new ToolResult(true, "echo ok", invocation.argumentsJson(), "[]", null);
    }
}
```

---

## 10）Memory / Workspace / Security

### `MemoryItem.java`

```java
package com.janyee.agent.memory;

public record MemoryItem(
        String id,
        String content
) {
}
```

### `MemoryQuery.java`

```java
package com.janyee.agent.memory;

public record MemoryQuery(
        String agentId,
        String query
) {
}
```

### `MemoryService.java`

```java
package com.janyee.agent.memory;

import java.util.List;

public interface MemoryService {
    List<MemoryItem> retrieve(MemoryQuery query);
}
```

### `WorkspaceService.java`

```java
package com.janyee.agent.workspace;

import java.nio.file.Path;

public interface WorkspaceService {
    Path getWorkspaceRoot(String agentId);
}
```

### `ApprovalDecision.java`

```java
package com.janyee.agent.security;

public enum ApprovalDecision {
    APPROVED,
    REJECTED
}
```

### `ApprovalService.java`

```java
package com.janyee.agent.security;

public interface ApprovalService {
    ApprovalDecision decide(String requestId);
}
```

---

## 11）Web 控制器：`agent-web`

### `agent-web/pom.xml`

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <parent>
        <groupId>com.janyee</groupId>
        <artifactId>ai-agent-platform</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <modelVersion>4.0.0</modelVersion>
    <artifactId>agent-web</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.janyee</groupId>
            <artifactId>agent-api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.janyee</groupId>
            <artifactId>agent-domain</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>com.janyee</groupId>
            <artifactId>agent-runtime</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
    </dependencies>
</project>
```

### `ChatController.java`

```java
package com.janyee.agent.web.controller;

import com.janyee.agent.api.ChatSendRequest;
import com.janyee.agent.api.ChatSendResponse;
import com.janyee.agent.domain.RunRequest;
import com.janyee.agent.runtime.AgentRunner;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentRunner agentRunner;

    public ChatController(AgentRunner agentRunner) {
        this.agentRunner = agentRunner;
    }

    @PostMapping("/send")
    public ChatSendResponse send(@RequestBody ChatSendRequest request) {
        String sessionId = request.sessionId() != null ? request.sessionId() : UUID.randomUUID().toString();
        return new ChatSendResponse(sessionId, "accepted");
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @RequestParam String sessionId,
            @RequestParam(required = false, defaultValue = "dev-agent") String agentId,
            @RequestParam(required = false, defaultValue = "anonymous") String userId,
            @RequestParam String message
    ) {
        RunRequest runRequest = new RunRequest(sessionId, agentId, userId, message);

        return agentRunner.run(runRequest)
                .map(event -> ServerSentEvent.builder(event.content())
                        .event(event.type().name())
                        .id(event.runId())
                        .build());
    }
}
```

---

## 12）README：`README.md`

```md
# ai-agent-platform

OpenClaw-like Java Agent 平台骨架工程。

## 技术栈
- Java 21
- Spring Boot 3
- Spring WebFlux
- Reactor
- Maven Multi-Module

## 当前状态
- 已完成多模块骨架
- 已提供基础领域模型
- 已提供 AgentRunner 接口
- 已提供 SSE 流式接口示例
- 已提供 Tool / Memory / Workspace / Security 占位接口

## 下一步
1. 加入 PostgreSQL 与 Flyway
2. 加入 Redis Session 锁
3. 实现真正的 PromptAssembler
4. 实现 OpenAI-compatible LlmProvider
5. 接入 tool policy 与 audit
```

---

## 13）最小运行方式

在项目根目录执行：

```bash
mvn clean package
```

启动：

```bash
cd agent-app
mvn spring-boot:run
```

测试流式接口：

```bash
curl "http://localhost:8080/api/chat/stream?sessionId=s1&message=hello"
```

---

## 14）说明

这份 A 当前是架构骨架，不是可生产运行版本。它已经把以下边界固定下来：

- Gateway 入口
- Runtime 接口
- AgentEvent 流
- Tool 抽象
- Memory 抽象
- Security 抽象
- Workspace 抽象
- 多模块 Maven 结构

还缺少这些真实落地部分：

- PostgreSQL 实体与表
- Redis Session 锁
- 真正的 PromptAssembler
- LLM Provider 实现
- Tool Registry
- 审计与审批落库
- 配置化 Agent/Workspace 加载

后续进入 Phase 1 可运行版时，再把这些部分补齐。


---

## 15）继续补充：Phase 1 可运行版落地内容

下面开始把这套骨架推进到 **Phase 1 可运行版**。目标是形成最小闭环：

**Web Chat -> Session -> Runtime -> LLM Provider -> SSE Stream -> Persistence**

---

## 16）新增模块职责细化

### `agent-infra`

建议把所有基础设施实现都收敛到这个模块：

- JPA Entity / Repository
- Redis Lock 实现
- LLM Provider 实现
- Tool Registry 实现
- Workspace 默认实现
- Memory 默认实现

这样 `agent-runtime` 只依赖接口，不依赖基础设施细节。

---

## 17）补充依赖建议

### 父 POM 增加版本属性

```xml
<properties>
    <java.version>21</java.version>
    <spring.boot.version>3.3.2</spring.boot.version>
    <flyway.version>10.17.0</flyway.version>
    <postgresql.version>42.7.3</postgresql.version>
</properties>
```

### `agent-app/pom.xml` 增加依赖

```xml
<dependency>
    <groupId>com.janyee</groupId>
    <artifactId>agent-infra</artifactId>
    <version>${project.version}</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
```

---

## 18）`application.yml` 扩展示例

```yaml
server:
  port: 8080

spring:
  application:
    name: ai-agent-platform
  main:
    web-application-type: reactive
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/agent
    username: agent
    password: agent123
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: none
    properties:
      hibernate:
        format_sql: true
    open-in-view: false
  flyway:
    enabled: true
    locations: classpath:db/migration
  data:
    redis:
      host: 127.0.0.1
      port: 6379

agent:
  workspace-root: ./workspaces
  default-agent-id: dev-agent
  runtime:
    max-tool-iterations: 5
    stream-buffer-size: 256
  llm:
    provider: openai-compatible
    model: gpt-4.1-mini
    base-url: https://api.openai.com
    api-key: ${OPENAI_API_KEY:}
    chat-path: /v1/chat/completions
  session:
    lock-ttl-seconds: 60

logging:
  level:
    root: INFO
    com.janyee.agent: INFO
```

---

## 19）Flyway SQL：`V1__init.sql`

建议放到：

```text
agent-app/src/main/resources/db/migration/V1__init.sql
```

内容如下：

```sql
create table if not exists agent_definition (
    id varchar(64) primary key,
    display_name varchar(128) not null,
    system_prompt text,
    workspace_path varchar(512) not null,
    status varchar(32) not null default 'ACTIVE',
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists session (
    id varchar(64) primary key,
    agent_id varchar(64) not null,
    user_id varchar(64),
    channel varchar(32) not null,
    status varchar(32) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists run_record (
    id varchar(64) primary key,
    session_id varchar(64) not null,
    agent_id varchar(64) not null,
    status varchar(32) not null,
    error_message text,
    started_at timestamp not null default current_timestamp,
    ended_at timestamp,
    usage_prompt_tokens integer,
    usage_completion_tokens integer
);

create table if not exists session_message (
    id bigserial primary key,
    session_id varchar(64) not null,
    run_id varchar(64),
    role varchar(32) not null,
    message_type varchar(32) not null,
    content text,
    tool_name varchar(128),
    tool_args_json text,
    tool_result_json text,
    seq_no integer not null,
    created_at timestamp not null default current_timestamp
);

create index if not exists idx_session_message_session_seq
    on session_message(session_id, seq_no);
```

---

## 20）JPA Entity 建议

### `SessionEntity.java`

```java
package com.janyee.agent.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "session")
public class SessionEntity {

    @Id
    private String id;

    @Column(name = "agent_id", nullable = false)
    private String agentId;

    @Column(name = "user_id")
    private String userId;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
```

### `SessionMessageEntity.java`

```java
package com.janyee.agent.infra.persistence.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "session_message")
public class SessionMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "run_id")
    private String runId;

    @Column(nullable = false)
    private String role;

    @Column(name = "message_type", nullable = false)
    private String messageType;

    @Column(columnDefinition = "text")
    private String content;

    @Column(name = "tool_name")
    private String toolName;

    @Column(name = "tool_args_json", columnDefinition = "text")
    private String toolArgsJson;

    @Column(name = "tool_result_json", columnDefinition = "text")
    private String toolResultJson;

    @Column(name = "seq_no", nullable = false)
    private Integer seqNo;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getToolArgsJson() {
        return toolArgsJson;
    }

    public void setToolArgsJson(String toolArgsJson) {
        this.toolArgsJson = toolArgsJson;
    }

    public String getToolResultJson() {
        return toolResultJson;
    }

    public void setToolResultJson(String toolResultJson) {
        this.toolResultJson = toolResultJson;
    }

    public Integer getSeqNo() {
        return seqNo;
    }

    public void setSeqNo(Integer seqNo) {
        this.seqNo = seqNo;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
```

---

## 21）Repository 建议

### `SessionRepository.java`

```java
package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionRepository extends JpaRepository<SessionEntity, String> {
}
```

### `SessionMessageRepository.java`

```java
package com.janyee.agent.infra.persistence.repository;

import com.janyee.agent.infra.persistence.entity.SessionMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SessionMessageRepository extends JpaRepository<SessionMessageEntity, Long> {

    List<SessionMessageEntity> findBySessionIdOrderBySeqNoAsc(String sessionId);

    @Query("select coalesce(max(m.seqNo), 0) from SessionMessageEntity m where m.sessionId = ?1")
    Integer findMaxSeqNo(String sessionId);
}
```

---

## 22）Session 锁接口补充

建议在 `agent-domain` 或 `agent-runtime` 增加：

```java
package com.janyee.agent.runtime.session;

import java.time.Duration;

public interface SessionLockManager {
    boolean tryLock(String sessionId, Duration timeout);
    void unlock(String sessionId);
}
```

### Redis 实现：`RedisSessionLockManager.java`

```java
package com.janyee.agent.infra.lock;

import com.janyee.agent.runtime.session.SessionLockManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RedisSessionLockManager implements SessionLockManager {

    private final StringRedisTemplate redisTemplate;

    public RedisSessionLockManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryLock(String sessionId, Duration timeout) {
        String key = "agent:session:lock:" + sessionId;
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, "1", timeout);
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public void unlock(String sessionId) {
        redisTemplate.delete("agent:session:lock:" + sessionId);
    }
}
```

---

## 23）PromptAssembler 默认实现

### `SimplePromptAssembler.java`

```java
package com.janyee.agent.infra.prompt;

import com.janyee.agent.domain.PromptContext;
import com.janyee.agent.domain.RunRequest;
import com.janyee.agent.runtime.prompt.PromptAssembler;
import org.springframework.stereotype.Component;

@Component
public class SimplePromptAssembler implements PromptAssembler {

    @Override
    public PromptContext assemble(RunRequest request) {
        String system = "You are a helpful Java agent.";
        String prompt = system + "
User: " + request.message();
        return new PromptContext(system, prompt);
    }
}
```

这只是占位实现。后续你应该继续把以下内容拼进去：

- system prompt
- soul prompt
- recent messages
- retrieved memory
- tool schemas
- workspace metadata

---

## 24）OpenAI-compatible Provider 设计

建议在 `agent-infra` 下新增：

- `OpenAiCompatibleProperties`
- `OpenAiCompatibleLlmProvider`
- `OpenAiChatRequest`
- `OpenAiChatResponse`

### 配置类示例

```java
package com.janyee.agent.infra.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.llm")
public class OpenAiCompatibleProperties {

    private String provider;
    private String model;
    private String baseUrl;
    private String apiKey;
    private String chatPath;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getChatPath() {
        return chatPath;
    }

    public void setChatPath(String chatPath) {
        this.chatPath = chatPath;
    }
}
```

### Provider 骨架

```java
package com.janyee.agent.infra.llm;

import com.janyee.agent.runtime.model.LlmChatRequest;
import com.janyee.agent.runtime.model.LlmProvider;
import com.janyee.agent.runtime.model.LlmStreamEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class OpenAiCompatibleLlmProvider implements LlmProvider {

    @Override
    public Flux<LlmStreamEvent> chatStream(LlmChatRequest request) {
        return Flux.just(
                new LlmStreamEvent("token", "[provider placeholder] "),
                new LlmStreamEvent("token", request.prompt())
        );
    }
}
```

先这样占位，下一阶段再换成基于 `WebClient` 的真实 SSE/stream 解析。

---

## 25）Runtime 真正闭环示例

把 `SimpleAgentRunner` 升级成真正依赖 PromptAssembler + LlmProvider。

```java
package com.janyee.agent.runtime.loop;

import com.janyee.agent.domain.AgentEvent;
import com.janyee.agent.domain.AgentEventType;
import com.janyee.agent.domain.PromptContext;
import com.janyee.agent.domain.RunRequest;
import com.janyee.agent.runtime.AgentRunner;
import com.janyee.agent.runtime.model.LlmChatRequest;
import com.janyee.agent.runtime.model.LlmProvider;
import com.janyee.agent.runtime.prompt.PromptAssembler;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.UUID;

@Component
public class SimpleAgentRunner implements AgentRunner {

    private final PromptAssembler promptAssembler;
    private final LlmProvider llmProvider;

    public SimpleAgentRunner(PromptAssembler promptAssembler, LlmProvider llmProvider) {
        this.promptAssembler = promptAssembler;
        this.llmProvider = llmProvider;
    }

    @Override
    public Flux<AgentEvent> run(RunRequest request) {
        String runId = UUID.randomUUID().toString();
        PromptContext promptContext = promptAssembler.assemble(request);

        Flux<AgentEvent> prefix = Flux.just(
                new AgentEvent(AgentEventType.RUN_STARTED, request.sessionId(), runId, "run started"),
                new AgentEvent(AgentEventType.RUN_STATUS, request.sessionId(), runId, "context built"),
                new AgentEvent(AgentEventType.RUN_STATUS, request.sessionId(), runId, "model running")
        );

        Flux<AgentEvent> body = llmProvider.chatStream(
                        new LlmChatRequest("default", promptContext.assembledPrompt())
                )
                .map(event -> new AgentEvent(
                        AgentEventType.TOKEN_DELTA,
                        request.sessionId(),
                        runId,
                        event.content()
                ));

        Flux<AgentEvent> suffix = Flux.just(
                new AgentEvent(AgentEventType.RUN_COMPLETED, request.sessionId(), runId, "completed")
        );

        return prefix.concatWith(body).concatWith(suffix);
    }
}
```

这时你的 `/api/chat/stream` 已经是一个真实的流式调用闭环，只是 LLM Provider 仍是占位版。

---

## 26）消息持久化服务建议

建议在 `agent-runtime` 增加一个接口：

```java
package com.janyee.agent.runtime.persistence;

public interface SessionTranscriptService {
    void appendUserMessage(String sessionId, String runId, String content);
    void appendAssistantMessage(String sessionId, String runId, String content, int seqNo);
}
```

在 `agent-infra` 里做实现，通过 `SessionMessageRepository` 落库。

### 基础实现思路

- Run 开始前写入 user message
- 流式 token 结束后聚合 assistant final text
- 最终只落一条 assistant message

首期不要把每个 token 都入库，不然表会很快膨胀。

---

## 27）Session 创建逻辑建议

在 `ChatController.send()` 里不要只返回 `accepted`，应当加入最小 session 初始化逻辑：

- sessionId 不存在则创建 session
- 绑定 agentId
- channel 固定为 `web`
- status 初始值为 `ACTIVE`

后续抽到 `SessionService`。

---

## 28）推荐新增服务接口

### `SessionService.java`

```java
package com.janyee.agent.runtime.session;

public interface SessionService {
    String initSession(String sessionId, String agentId, String userId, String channel);
    boolean exists(String sessionId);
}
```

### `AgentDefinitionService.java`

```java
package com.janyee.agent.runtime.agent;

import com.janyee.agent.domain.AgentDefinition;

public interface AgentDefinitionService {
    AgentDefinition getById(String agentId);
}
```

---

## 29）首期推荐最小可运行闭环

当你完成以下 8 项时，这个项目就从“骨架”进入“能跑”：

1. Flyway SQL 初始化库表
2. SessionEntity / SessionMessageEntity
3. SessionRepository / SessionMessageRepository
4. SessionService 默认实现
5. PromptAssembler 默认实现
6. OpenAI-compatible Provider 占位实现
7. SimpleAgentRunner 真实串联 Prompt + Provider
8. ChatController 完成 initSession + stream

---

## 30）面向 Codex 的类设计说明写法

从这里开始，不再继续补“实现代码”，而是改为输出 **Codex 友好的系统设计规格**。

写法规则如下：

- 每个类单独一个小节
- 固定字段顺序，避免自然语言跳跃
- 明确区分“职责”和“非职责”
- 给出输入、输出、依赖、状态、约束、失败场景
- 给出建议的方法名，但不写具体实现
- 避免把多个类混在一个段落中

建议 Codex 读取格式：

```text
ClassName:
  Module:
  Type:
  Purpose:
  Responsibilities:
  NonResponsibilities:
  Inputs:
  Outputs:
  Dependencies:
  State:
  KeyMethods:
  Constraints:
  FailureModes:
  Persistence:
  Observability:
  ImplementationNotes:
```

---

## 31）顶层模块说明（Codex 入口说明）

### Module: `agent-app`

Purpose:
- 应用启动与装配入口。
- 负责加载 Spring Boot 配置、启用自动配置、注册 Bean。

Contains:
- `AgentApplication`
- 配置文件 `application.yml`
- Flyway migration 资源

NonResponsibilities:
- 不承担业务逻辑。
- 不直接处理 Session、Prompt、Tool、LLM 调用。

### Module: `agent-api`

Purpose:
- 定义对外 API 的请求与响应模型。
- 与领域模型分离，避免 Web 层直接暴露内部对象。

Contains:
- `ChatSendRequest`
- `ChatSendResponse`

NonResponsibilities:
- 不做业务判断。
- 不持久化。

### Module: `agent-domain`

Purpose:
- 定义系统核心领域对象与枚举。
- 为 Runtime、Tool、Web 层提供统一语义。

Contains:
- Agent / Run / Tool / Event 相关对象

NonResponsibilities:
- 不依赖基础设施。
- 不实现数据库、网络或缓存。

### Module: `agent-runtime`

Purpose:
- 定义 Agent 执行内核接口。
- 负责编排 Prompt、LLM、Tool、Session 生命周期。

Contains:
- `AgentRunner`
- `PromptAssembler`
- `LlmProvider`
- Session / Agent / Persistence service interfaces

NonResponsibilities:
- 不直接依赖具体 OpenAI SDK、Redis SDK、JPA Entity。

### Module: `agent-tool`

Purpose:
- 定义 Tool 抽象与策略接口。
- 管理工具的统一调用协议。

### Module: `agent-memory`

Purpose:
- 定义 Memory 检索与写入抽象。

### Module: `agent-workspace`

Purpose:
- 定义 Workspace 路径与文件读取边界。

### Module: `agent-security`

Purpose:
- 定义审批与安全决策抽象。

### Module: `agent-web`

Purpose:
- 提供 REST / SSE / WebSocket 入口。
- 负责请求校验与协议映射。

### Module: `agent-infra`

Purpose:
- 提供 JPA / Redis / WebClient / 本地文件系统等基础设施实现。

---

## 32）类设计说明：`agent-app`

### ClassName: `AgentApplication`

Module:
- `agent-app`

Type:
- Spring Boot 启动类

Purpose:
- 启动整个系统。
- 作为应用装配入口。

Responsibilities:
- 启动 Spring 容器。
- 指定扫描包范围。
- 触发基础设施与 Web 层装配。

NonResponsibilities:
- 不写任何业务逻辑。
- 不创建 Session。
- 不处理 Agent Run。

Inputs:
- JVM 启动参数
- Spring 配置文件

Outputs:
- 已启动的 Spring Boot 应用

Dependencies:
- Spring Boot

State:
- 无业务状态

KeyMethods:
- `main(String[] args)`

Constraints:
- 必须保持极简。
- 不允许在启动类中手工 new 业务对象。

FailureModes:
- 配置缺失导致启动失败
- Bean 装配冲突导致启动失败

Persistence:
- 无

Observability:
- 使用 Spring Boot 标准启动日志

ImplementationNotes:
- 只保留 `@SpringBootApplication(scanBasePackages = "com.janyee.agent")`

---

## 33）类设计说明：`agent-api`

### ClassName: `ChatSendRequest`

Module:
- `agent-api`

Type:
- API DTO

Purpose:
- 表示一次发送聊天消息的请求。

Responsibilities:
- 承载 `sessionId`、`agentId`、`userId`、`message` 四个字段。
- 与 HTTP 请求体一一对应。

NonResponsibilities:
- 不做业务校验以外的逻辑。
- 不负责创建 Session。

Inputs:
- HTTP JSON body

Outputs:
- 供 Controller 读取的结构化字段

Dependencies:
- 无

State:
- 不可变

KeyMethods:
- record 默认访问器即可

Constraints:
- `message` 不能为空
- `agentId` 可为空，空时由系统填默认值
- `sessionId` 可为空，空时由系统创建

FailureModes:
- JSON 解析失败
- 字段缺失

Persistence:
- 无

Observability:
- 不记录日志，交给 Controller

ImplementationNotes:
- 推荐增加 Bean Validation 注解，例如 `@NotBlank` 标记 `message`

### ClassName: `ChatSendResponse`

Module:
- `agent-api`

Type:
- API DTO

Purpose:
- 表示发送消息接口的响应。

Responsibilities:
- 返回 `sessionId` 与请求受理状态。

NonResponsibilities:
- 不返回完整运行结果。

Inputs:
- Controller 填充

Outputs:
- JSON 响应

Dependencies:
- 无

State:
- 不可变

KeyMethods:
- record 默认访问器即可

Constraints:
- `sessionId` 必须始终存在
- `status` 推荐只用有限枚举值，如 `accepted`

FailureModes:
- 无自身失败逻辑

Persistence:
- 无

Observability:
- 无

ImplementationNotes:
- 保持最小响应模型，复杂状态通过 SSE / WebSocket 输出

---

## 34）类设计说明：`agent-domain`

### ClassName: `AgentDefinition`

Module:
- `agent-domain`

Type:
- 领域对象

Purpose:
- 描述一个 Agent 的静态定义。

Responsibilities:
- 保存 Agent 的 ID、展示名、System Prompt、Workspace 路径。
- 为 Runtime 提供装配 Prompt 的基础信息。

NonResponsibilities:
- 不从数据库读取自己。
- 不决定工具权限。

Inputs:
- 来自 `AgentDefinitionService`

Outputs:
- 供 Runtime / PromptAssembler 使用

Dependencies:
- 无

State:
- 不可变

KeyMethods:
- record 默认访问器即可

Constraints:
- `id` 必须唯一
- `workspacePath` 必须能映射到受控目录

FailureModes:
- 无自身失败逻辑

Persistence:
- 对应 `agent_definition` 表，但对象本身不关心持久化

Observability:
- 无

ImplementationNotes:
- 后续可扩展字段：`toolPolicyId`、`modelProfileId`、`status`

### ClassName: `IncomingMessage`

Purpose:
- 统一表达来自任意 Channel 的入站消息。

Responsibilities:
- 表示消息来源渠道、用户、Session、内容。
- 作为 Router 的输入模型。

NonResponsibilities:
- 不承担 API DTO 职责。
- 不携带复杂领域状态。

ImplementationNotes:
- 后续可增加 `metadataJson` 字段承载 chatId、headers、sourceMessageId

### ClassName: `AgentBinding`

Purpose:
- 表示 Router 解析后的路由结果。

Responsibilities:
- 指明该消息应进入哪个 Agent。

NonResponsibilities:
- 不负责路由计算。

ImplementationNotes:
- 后续可扩展 `workspaceOverride`、`modelOverride`

### ClassName: `RunRequest`

Purpose:
- 表示一次 Agent Run 的最小输入。

Responsibilities:
- 封装运行时需要的 Session、Agent、User、Message 信息。

NonResponsibilities:
- 不包含历史上下文。
- 不包含 Prompt。

ImplementationNotes:
- 未来可扩展 `channel`、`requestMetadata`

### ClassName: `PromptContext`

Purpose:
- 表示 PromptAssembler 的结果。

Responsibilities:
- 提供最终 system prompt 与 assembled prompt。

NonResponsibilities:
- 不负责调用模型。

ImplementationNotes:
- 未来可扩展：`messages`、`toolSchemas`、`memoryFragments`

### ClassName: `ToolSchema`

Purpose:
- 描述 Tool 的结构化定义。

Responsibilities:
- 提供 name、description、jsonSchema。

NonResponsibilities:
- 不执行工具。

### ClassName: `ToolInvocation`

Purpose:
- 表示一次工具调用请求。

Responsibilities:
- 提供 session、toolName、argumentsJson。

NonResponsibilities:
- 不做参数校验。

ImplementationNotes:
- 后续建议增加 `runId`、`agentId`、`userId`

### ClassName: `ToolResult`

Purpose:
- 表示工具执行结果。

Responsibilities:
- 给 Runtime 返回统一结构：成功状态、摘要、数据、附件、错误。

NonResponsibilities:
- 不直接写数据库。

Constraints:
- `summary` 必须简明，适合再次拼入 Prompt
- `dataJson` 必须是合法 JSON 字符串

ImplementationNotes:
- 后续建议把 `dataJson` 改成对象，再由基础设施层序列化

### ClassName: `AgentEvent`

Purpose:
- 表示 Runtime 向外输出的统一事件。

Responsibilities:
- 承载事件类型、Session、Run、内容。

NonResponsibilities:
- 不保存完整上下文。

ImplementationNotes:
- 后续建议增加 `timestamp`、`metadataJson`

### Enum: `AgentEventType`

Purpose:
- 约束外发事件类型。

Responsibilities:
- 作为 SSE/WebSocket 的标准 event 名集合。

RecommendedValues:
- `RUN_STARTED`
- `RUN_STATUS`
- `TOKEN_DELTA`
- `TOOL_REQUESTED`
- `TOOL_STARTED`
- `TOOL_COMPLETED`
- `RUN_COMPLETED`
- `RUN_FAILED`

### Enum: `RunStatus`

Purpose:
- 表示 Run 内部状态机。

Responsibilities:
- 描述 Agent Loop 当前阶段。

RecommendedValues:
- `RECEIVED`
- `CONTEXT_BUILT`
- `MODEL_RUNNING`
- `TOOL_REQUESTED`
- `TOOL_EXECUTING`
- `TOOL_RESULT_APPENDED`
- `COMPLETED`
- `FAILED`
- `WAITING_APPROVAL`

### Enum: `ToolRiskLevel`

Purpose:
- 表示工具风险等级。

Responsibilities:
- 供 ToolPolicy / Approval 使用。

RecommendedValues:
- `SAFE`
- `GUARDED`
- `PRIVILEGED`
- `APPROVAL_REQUIRED`

---

## 35）类设计说明：`agent-runtime`

### Interface: `AgentRunner`

Module:
- `agent-runtime`

Type:
- Runtime 核心接口

Purpose:
- 驱动一次 Agent Run。

Responsibilities:
- 接收 `RunRequest`
- 组织 PromptAssembler、LLM Provider、Tool Loop
- 输出连续 `AgentEvent` 流

NonResponsibilities:
- 不关心 HTTP 协议细节
- 不直接使用 JPA Entity

Inputs:
- `RunRequest`

Outputs:
- `Flux<AgentEvent>`

Dependencies:
- `PromptAssembler`
- `LlmProvider`
- 后续可依赖 `ToolRegistry`、`SessionTranscriptService`

State:
- 无长期状态；单次 run 内可有局部变量

KeyMethods:
- `run(RunRequest request)`

Constraints:
- 必须是 Session 串行上下文内执行
- 输出事件顺序必须稳定

FailureModes:
- Prompt 装配失败
- Provider 调用失败
- Tool 调用失败

Persistence:
- 通过依赖的 persistence service 完成，不直接落库

Observability:
- 关键阶段输出状态事件和日志

ImplementationNotes:
- 真实实现应拆成状态机，而不是一个大方法

### ClassName: `SimpleAgentRunner`

Type:
- `AgentRunner` 的 Phase 1 默认实现

Purpose:
- 提供最小可运行的 Agent Loop。

Responsibilities:
- 生成 `runId`
- 调 `PromptAssembler`
- 调 `LlmProvider`
- 把流式结果映射为 `AgentEvent`
- 输出 started / completed 等生命周期事件

NonResponsibilities:
- 不处理复杂 Tool Loop
- 不承担 Session 锁逻辑

Dependencies:
- `PromptAssembler`
- `LlmProvider`

State:
- 单次运行内的 `runId`、`PromptContext`

KeyMethods:
- `run(RunRequest request)`

Constraints:
- 只做最小闭环，不做多轮工具调用

ImplementationNotes:
- 后续建议拆分为：
  - `RunLifecycleService`
  - `ToolLoopExecutor`
  - `RunEventPublisher`

### Interface: `PromptAssembler`

Purpose:
- 把运行上下文装配成最终 Prompt。

Responsibilities:
- 合并 system prompt、memory、recent messages、tool schemas、workspace metadata

NonResponsibilities:
- 不调用模型
- 不检索数据库实体细节；应通过 service 获取

Inputs:
- `RunRequest`

Outputs:
- `PromptContext`

Dependencies:
- 后续可依赖 `AgentDefinitionService`、`MemoryService`、`WorkspaceService`

Constraints:
- 输出结果必须 deterministic，可重复

ImplementationNotes:
- Prompt 组装必须集中，禁止分散到 Controller 或 Provider

### ClassName: `SimplePromptAssembler`

Type:
- `PromptAssembler` 的 Phase 1 默认实现

Purpose:
- 提供最小 Prompt 组装能力。

Responsibilities:
- 生成简单 system prompt
- 拼接用户消息

NonResponsibilities:
- 不检索 memory
- 不加载 workspace 文件

### Interface: `LlmProvider`

Purpose:
- 统一不同模型服务的调用方式。

Responsibilities:
- 接收标准化 `LlmChatRequest`
- 输出统一流式事件 `Flux<LlmStreamEvent>`

NonResponsibilities:
- 不参与 Prompt 组装
- 不决定 Tool 调用策略

Constraints:
- 必须屏蔽不同模型厂商差异
- 必须统一错误语义

### ClassName: `LlmChatRequest`

Purpose:
- 表示对模型的一次请求。

Responsibilities:
- 承载 model 与 prompt

ImplementationNotes:
- 未来建议扩展为 messages、temperature、tool schemas、metadata

### ClassName: `LlmStreamEvent`

Purpose:
- 表示模型层流式返回事件。

Responsibilities:
- 统一 token / tool_call / finish / error 类型

ImplementationNotes:
- 当前 `type + content` 太简化，后续可拆成层级对象

### Interface: `SessionLockManager`

Purpose:
- 为同一 Session 提供串行执行保障。

Responsibilities:
- 尝试加锁
- 释放锁

NonResponsibilities:
- 不创建 Session
- 不管理消息队列

Constraints:
- 锁必须有 TTL
- 必须避免死锁长期占用

### Interface: `SessionService`

Purpose:
- 管理 Session 生命周期。

Responsibilities:
- 初始化 Session
- 查询 Session 是否存在
- 后续可扩展更新状态

NonResponsibilities:
- 不处理消息流式输出

### Interface: `SessionTranscriptService`

Purpose:
- 管理会话 transcript 的持久化。

Responsibilities:
- 追加 user message
- 追加 assistant message
- 未来可追加 tool message

Constraints:
- 需要维护 `seqNo` 单调递增

### Interface: `AgentDefinitionService`

Purpose:
- 提供 AgentDefinition 查询能力。

Responsibilities:
- 按 `agentId` 返回 Agent 定义

NonResponsibilities:
- 不做路由

---

## 36）类设计说明：`agent-tool`

### Interface: `AgentTool`

Purpose:
- 定义所有工具的统一协议。

Responsibilities:
- 暴露工具名称
- 暴露工具 schema
- 接收 invocation 并返回 result

NonResponsibilities:
- 不决定自己是否允许执行；那是 ToolPolicy 的职责

Inputs:
- `ToolInvocation`

Outputs:
- `ToolResult`

Constraints:
- 工具实现应尽量幂等
- 返回值必须结构化

ImplementationNotes:
- 高风险工具不要直接在主 JVM 中执行

### ClassName: `EchoTool`

Purpose:
- 作为最小内置示例工具。

Responsibilities:
- 原样返回输入参数

NonResponsibilities:
- 不用于真实业务

ImplementationNotes:
- 主要用于验证 Tool Loop 线路是否通畅

### Interface: `ToolPolicyService`

Purpose:
- 决定某 Agent 是否允许调用某 Tool。

Responsibilities:
- 根据 agentId、toolName 返回 allow/deny

NonResponsibilities:
- 不执行工具
- 不做审批交互

ImplementationNotes:
- 未来可扩展输入：risk level、user role、workspace policy

---

## 37）类设计说明：`agent-memory`

### ClassName: `MemoryItem`

Purpose:
- 表示一条可检索记忆片段。

Responsibilities:
- 提供最小标识与内容

ImplementationNotes:
- 后续建议增加 `score`、`sourceType`、`sourceId`

### ClassName: `MemoryQuery`

Purpose:
- 表示一次记忆检索请求。

Responsibilities:
- 指定 agentId 与 query

### Interface: `MemoryService`

Purpose:
- 对 Runtime 提供记忆检索能力。

Responsibilities:
- 根据 Query 返回 MemoryItem 列表

NonResponsibilities:
- 不决定 Prompt 如何拼装

ImplementationNotes:
- 后续可拆为 `MemoryRetrievalService` 与 `MemoryWriteService`

---

## 38）类设计说明：`agent-workspace`

### Interface: `WorkspaceService`

Purpose:
- 对 Runtime 提供受控 Workspace 访问入口。

Responsibilities:
- 根据 agentId 返回 workspace root
- 未来可扩展读取 AGENT.md、SOUL.md、TOOLS.yaml、MEMORY.md

NonResponsibilities:
- 不直接暴露任意文件系统路径

Constraints:
- 必须防止路径逃逸
- 必须限定在配置的 workspace root 下

ImplementationNotes:
- 后续建议增加：
  - `readAgentFile(agentId, fileName)`
  - `listKnowledgeFiles(agentId)`
  - `saveArtifact(agentId, fileName, bytes)`

---

## 39）类设计说明：`agent-security`

### Enum: `ApprovalDecision`

Purpose:
- 表示审批结果。

Responsibilities:
- 约束审批流返回值为有限集合

### Interface: `ApprovalService`

Purpose:
- 为高风险操作提供审批能力。

Responsibilities:
- 根据 requestId 返回审批结果
- 后续可扩展创建审批请求与监听结果

NonResponsibilities:
- 不执行具体工具

ImplementationNotes:
- Phase 1 可仅保留接口，不实现完整审批流

---

## 40）类设计说明：`agent-web`

### ClassName: `ChatController`

Module:
- `agent-web`

Type:
- REST / SSE Controller

Purpose:
- 提供聊天发送与流式读取入口。

Responsibilities:
- 接收 `ChatSendRequest`
- 初始化 Session 或确认已有 Session
- 将请求映射为 `RunRequest`
- 调用 `AgentRunner`
- 把 `AgentEvent` 映射为 SSE 事件

NonResponsibilities:
- 不写 Prompt 逻辑
- 不直接操作数据库 Entity
- 不实现 Tool Loop

Inputs:
- HTTP Request

Outputs:
- JSON 响应
- SSE 事件流

Dependencies:
- `AgentRunner`
- 后续可依赖 `SessionService`

State:
- 无持久业务状态

KeyMethods:
- `send(ChatSendRequest request)`
- `stream(...)`

Constraints:
- Controller 只能做协议适配，不做复杂业务判断

FailureModes:
- 请求体非法
- Session 初始化失败
- Runtime 执行失败

Persistence:
- 不直接持久化；通过 Service 完成

Observability:
- 记录请求入口、sessionId、agentId、失败原因

ImplementationNotes:
- 最终建议把 `/send` 和 `/stream` 改成一个“创建 run + 订阅 run”的更清晰协议

---

## 41）类设计说明：`agent-infra`

### ClassName: `SessionEntity`

Module:
- `agent-infra`

Type:
- JPA Entity

Purpose:
- 映射 `session` 表。

Responsibilities:
- 保存 Session 的结构化持久化字段。

NonResponsibilities:
- 不写业务逻辑。

Fields:
- `id`
- `agentId`
- `userId`
- `channel`
- `status`
- `createdAt`
- `updatedAt`

Constraints:
- 字段必须与数据库表严格对应

ImplementationNotes:
- 不要在 Entity 中加入复杂领域方法

### ClassName: `SessionMessageEntity`

Purpose:
- 映射 `session_message` 表。

Responsibilities:
- 持久化 transcript 消息与 tool 结果。

Fields:
- `id`
- `sessionId`
- `runId`
- `role`
- `messageType`
- `content`
- `toolName`
- `toolArgsJson`
- `toolResultJson`
- `seqNo`
- `createdAt`

Constraints:
- `seqNo` 在同一 Session 内单调递增

### Interface/Class: `SessionRepository`

Purpose:
- 访问 `session` 表。

Responsibilities:
- 按 ID 查询与保存 SessionEntity

### Interface/Class: `SessionMessageRepository`

Purpose:
- 访问 `session_message` 表。

Responsibilities:
- 查询 Session 全部消息
- 查询最大 `seqNo`
- 保存新消息

### ClassName: `RedisSessionLockManager`

Purpose:
- 基于 Redis 实现 Session 串行锁。

Responsibilities:
- 使用 `SETNX + EXPIRE` 语义完成加锁
- 在 run 结束后释放锁

NonResponsibilities:
- 不决定何时创建 Session

Dependencies:
- `StringRedisTemplate`

Constraints:
- 锁 key 命名必须稳定
- TTL 必须可配置

FailureModes:
- Redis 不可用
- 重入失败

ImplementationNotes:
- 未来可增加锁拥有者 token，防止误删他人锁

### ClassName: `OpenAiCompatibleProperties`

Purpose:
- 承载 LLM Provider 配置。

Responsibilities:
- 提供 provider、model、baseUrl、apiKey、chatPath

NonResponsibilities:
- 不发请求

### ClassName: `OpenAiCompatibleLlmProvider`

Purpose:
- 提供 OpenAI-compatible 协议模型接入。

Responsibilities:
- 基于统一接口发送 chat 请求
- 解析流式返回
- 转换为 `LlmStreamEvent`

NonResponsibilities:
- 不决定 Prompt 内容
- 不决定事件如何映射成 SSE

Dependencies:
- `WebClient`
- `OpenAiCompatibleProperties`

Constraints:
- 必须能兼容 OpenAI / vLLM / LocalAI / OneAPI 等兼容接口

FailureModes:
- HTTP 超时
- 鉴权失败
- 流式响应中断

ImplementationNotes:
- Phase 1 可先用占位实现，Phase 2 再接真实流式解析

### ClassName: `SimplePromptAssembler`

Purpose:
- 见 runtime 部分；其实现放在 infra 中是因为它依赖配置与基础设施。

### ClassName: `SessionServiceImpl`

Purpose:
- `SessionService` 的默认实现。

Responsibilities:
- 若 session 不存在则创建
- 若存在则原样返回
- 设置默认 agentId / channel / status

Dependencies:
- `SessionRepository`

Constraints:
- 初始化逻辑必须幂等

ImplementationNotes:
- 后续可增加 lastActiveAt 更新

### ClassName: `SessionTranscriptServiceImpl`

Purpose:
- `SessionTranscriptService` 的默认实现。

Responsibilities:
- 保存 user / assistant transcript
- 维护 `seqNo`

Dependencies:
- `SessionMessageRepository`

Constraints:
- 同一 Session 内必须保证顺序正确

ImplementationNotes:
- Phase 1 只在 final assistant text 完成后写一条 assistant message

### ClassName: `AgentDefinitionServiceImpl`

Purpose:
- `AgentDefinitionService` 的默认实现。

Responsibilities:
- 从数据库或配置读取 AgentDefinition
- 对缺失 agentId 做明确失败

Dependencies:
- 初期可依赖 `SessionRepository` 以外的独立 AgentDefinition repository

ImplementationNotes:
- 若首期没有 `agent_definition` 表，也可先做基于配置文件的实现

---

## 42）给 Codex 的生成顺序建议

建议按下面顺序让 Codex 逐步生成，而不是一次性生成整个工程：

### Step 1：领域模型
生成：
- `agent-api`
- `agent-domain`

目标：
- 先把系统输入输出语义定死

### Step 2：Runtime 接口
生成：
- `AgentRunner`
- `PromptAssembler`
- `LlmProvider`
- `SessionService`
- `SessionTranscriptService`
- `AgentDefinitionService`
- `SessionLockManager`

目标：
- 定死内核边界

### Step 3：Web 层
生成：
- `ChatController`

目标：
- 打通最小 API 入口

### Step 4：基础设施持久化
生成：
- Entity
- Repository
- Flyway SQL

目标：
- 能落 Session 与 Transcript

### Step 5：Provider 与 Prompt 默认实现
生成：
- `SimplePromptAssembler`
- `OpenAiCompatibleLlmProvider`
- `SimpleAgentRunner`

目标：
- 打通最小聊天流

### Step 6：Session 锁
生成：
- `RedisSessionLockManager`

目标：
- 保证串行运行

### Step 7：Tool 体系
生成：
- `AgentTool`
- `ToolPolicyService`
- `EchoTool`
- Tool Registry

目标：
- 为下一阶段 Tool Loop 做准备

---

## 43）给 Codex 的统一生成约束

为了降低 Codex 生成偏差，建议附带以下统一要求：

```text
GlobalConstraints:
  - Use Java 21.
  - Use Spring Boot 3.x.
  - Use Spring WebFlux for controllers.
  - Keep runtime interfaces independent from infrastructure implementations.
  - Do not put business logic in controllers.
  - Do not put business logic in JPA entities.
  - Use clear package names under com.janyee.agent.
  - Prefer immutable DTOs and domain records.
  - Keep Phase 1 implementation minimal but runnable.
  - Do not implement complex tool loop yet.
  - Session execution must be serializable through SessionLockManager.
  - SSE event output must be based on AgentEvent.
```

---

## 44）总结：这份规格的用途

这份类设计说明不是给人“阅读概念”的，而是给 Codex 做生成控制的。它的目的有三个：

1. 先把 **每个类的边界** 定死，避免 Codex 随意混职责。
2. 先把 **接口与实现分层** 定死，避免生成强耦合结构。
3. 先把 **Phase 1 范围** 定死，避免一上来生成过重系统。

如果后续继续扩展，建议下一份文档专门写：

- Tool Loop 状态机类说明
- Memory 分层类说明
- Approval / Audit 类说明
- Multi-Agent Router 类说明

这样 Codex 能按文档分批生成，而不是一次性失控。


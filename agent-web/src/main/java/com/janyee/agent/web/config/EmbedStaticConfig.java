package com.janyee.agent.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;

/**
 * 暴露 web-console 静态资源到后端 8080:
 *   /embed/*         → embedDir(默认 web-console/dist/embed/)
 *   /assets/*        → spaDir/assets/
 *   /icon.png 等     → spaDir/<file>
 *   其他非 /api /oauth /ws → 兜底返回 index.html(让前端 vue-router 自己接管)
 *
 * <p>有了 SPA fallback,第三方 iframe 可以指 baseUrl/?embed=1,主控台 App.vue
 * 检测到 embed 标志后会精简渲染成纯聊天面板。</p>
 */
@Configuration
public class EmbedStaticConfig implements WebFluxConfigurer {

    private final String embedDir;
    private final String spaDir;

    public EmbedStaticConfig(
            @Value("${agent.embed.static-dir:./web-console/dist/embed/}") String embedDir,
            @Value("${agent.web-console.static-dir:./web-console/dist/}") String spaDir
    ) {
        this.embedDir = embedDir.endsWith("/") ? embedDir : (embedDir + "/");
        this.spaDir = spaDir.endsWith("/") ? spaDir : (spaDir + "/");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/embed/**")
                .addResourceLocations("file:" + embedDir);
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("file:" + spaDir + "assets/");
        // 其它根目录文件(icon.png / favicon.ico / index.html 等)
        registry.addResourceHandler("/*.png", "/*.ico", "/*.svg", "/*.txt")
                .addResourceLocations("file:" + spaDir);
        // 显式 / 入口
        registry.addResourceHandler("/index.html", "/")
                .addResourceLocations("file:" + spaDir);
    }

    /**
     * 放开 JSON 请求体内存上限 + multipart 字段上限 —— 默认 256KB 太小,base64 附件
     * (历史路径)/ 大 chat history 都会被卡。改成 64MB。
     * 真正的大文件(几百 MB)走 /api/chat/attachments 的 multipart 流式 transferTo,
     * 不进 JVM 堆,所以这里 64MB 是给"非流式 in-memory codec"的兜底。
     */
    @Override
    public void configureHttpMessageCodecs(org.springframework.http.codec.ServerCodecConfigurer configurer) {
        configurer.defaultCodecs().maxInMemorySize(64 * 1024 * 1024);
        // multipart 单 part 内存上限同样放开(FilePart 不受影响,它走流式 transferTo 落盘)
        configurer.defaultCodecs().enableLoggingRequestDetails(false);
    }

    /**
     * SPA fallback:任意非 /api /oauth /ws /embed /assets 的 GET 请求都返回 index.html,
     * 让 vue-router 自己接管(/chat/xxx 这类深链接不需要后端建路由)。
     * 用 RouterFunction 写,优先级低于 controller(controller 显式 mapping 不会被吃掉)。
     */
    @Bean
    public RouterFunction<ServerResponse> spaFallbackRouter() {
        Resource indexHtml = new FileSystemResource(spaDir + "index.html");
        return RouterFunctions.route(
                GET("/").or(GET("/chat/**")).or(GET("/search")).or(GET("/approvals"))
                        .or(GET("/memory")).or(GET("/agents")).or(GET("/llms"))
                        .or(GET("/knowledge")).or(GET("/tools")).or(GET("/skills"))
                        .or(GET("/datasources")).or(GET("/users")).or(GET("/tenants"))
                        .or(GET("/roles")).or(GET("/oauth-clients")).or(GET("/login")),
                req -> ServerResponse.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .body(BodyInserters.fromResource(indexHtml))
        );
    }
}

package com.logassistant.tool;

import com.logassistant.service.KibanaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 可调用的日志查询工具。
 * Cookie 通过 AtomicReference 传入，避免作为 Tool 参数暴露给 AI。
 * 使用 AtomicReference 而非 ThreadLocal，因为 Reactor 异步线程间 ThreadLocal 不会传递。
 * 如果用户未设置 Cookie，则使用配置文件中的默认值。
 */
@Slf4j
@Component
public class LogQueryTool {

    private final KibanaService kibanaService;

    @Value("${kibana.default-cookie:}")
    private String defaultCookie;

    // 使用 AtomicReference 替代 ThreadLocal，确保跨 Reactor 线程可见
    private static final AtomicReference<String> COOKIE_HOLDER = new AtomicReference<>();
    private static final AtomicReference<String> NAMESPACE_HOLDER = new AtomicReference<>();
    private static final AtomicReference<String> CONTAINER_HOLDER = new AtomicReference<>();

    public LogQueryTool(KibanaService kibanaService) {
        this.kibanaService = kibanaService;
    }

    public static void setCookie(String cookie) {
        COOKIE_HOLDER.set(cookie);
    }

    public static void clearCookie() {
        COOKIE_HOLDER.set(null);
    }

    public static void setNamespaceName(String namespaceName) {
        NAMESPACE_HOLDER.set(namespaceName);
    }

    public static void clearNamespaceName() {
        NAMESPACE_HOLDER.set(null);
    }

    public static void setContainerName(String containerName) {
        CONTAINER_HOLDER.set(containerName);
    }

    public static void clearContainerName() {
        CONTAINER_HOLDER.set(null);
    }

    @Tool(description = "查询应用日志。当用户想要查找、搜索、检索日志信息时调用此工具。" +
            "可以根据关键词、时间范围和索引名进行搜索。" +
            "支持多个关键词用空格分隔，会同时匹配所有关键词。")
    public String searchLogs(
            @ToolParam(description = "搜索关键词，如错误信息、交易号、类名等。多个关键词用空格分隔。") String keyword,
            @ToolParam(description = "查询开始时间，ISO 8601 格式，如 2026-02-13T00:00:00Z。不指定则默认最近24小时。", required = false) String startTime,
            @ToolParam(description = "查询结束时间，ISO 8601 格式，如 2026-02-14T00:00:00Z。不指定则默认当前时间。", required = false) String endTime,
            @ToolParam(description = "Elasticsearch 索引名称，如 app_logs_index。不指定则使用默认索引。", required = false) String index,
            @ToolParam(description = "返回的日志条数，默认100，最大500。", required = false) Integer size) {

        // 优先使用用户传入的 cookie，为空则使用默认配置
        String cookie = COOKIE_HOLDER.get();
        if (cookie == null || cookie.isBlank()) {
            cookie = defaultCookie;
            if (cookie == null || cookie.isBlank()) {
                return "错误：未提供 Kibana Cookie，请在页面设置中配置 Cookie，或在 application.yml 中设置 kibana.default-cookie。";
            }
            log.info("用户未设置 Cookie，使用默认配置的 Cookie");
        }

        log.info("AI 调用日志查询工具: keyword={}, startTime={}, endTime={}, index={}, size={}",
                keyword, startTime, endTime, index, size);

        return kibanaService.searchLogs(
                keyword,
                startTime,
                endTime,
                index,
                cookie,
                size != null ? size : 100,
                NAMESPACE_HOLDER.get(),
                CONTAINER_HOLDER.get());
    }
}

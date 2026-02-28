package com.logassistant.controller;

import com.logassistant.tool.LogQueryTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final int MAX_RETRIES = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(2);

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(
            @RequestParam String message,
            @RequestParam(required = false, defaultValue = "") String cookie,
            @RequestParam(required = false, defaultValue = "") String namespaceName,
            @RequestParam(required = false, defaultValue = "") String containerName) {

        // 注入当前时间到消息中，让 AI 能处理相对时间
        String currentTime = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String enrichedMessage = String.format("[当前时间: %s]\n\n%s", currentTime, message);

        // 设置 Cookie 及其他参数到 ThreadLocal/AtomicReference
        LogQueryTool.setCookie(cookie);
        LogQueryTool.setNamespaceName(namespaceName);
        LogQueryTool.setContainerName(containerName);

        log.info("收到聊天请求: message={}", message);

        return chatClient.prompt()
                .user(enrichedMessage)
                .stream()
                .content()
                .retryWhen(Retry.backoff(MAX_RETRIES, INITIAL_BACKOFF)
                        .filter(throwable -> throwable instanceof WebClientResponseException.TooManyRequests)
                        .doBeforeRetry(signal -> log.warn("OpenAI API 返回 429 Too Many Requests，第 {} 次重试，等待退避后重试...",
                                signal.totalRetries() + 1))
                        .onRetryExhaustedThrow((spec, signal) -> {
                            log.error("OpenAI API 重试 {} 次后仍然返回 429，放弃重试", MAX_RETRIES);
                            return signal.failure();
                        }))
                .doOnError(e -> {
                    if (e instanceof WebClientResponseException wce) {
                        log.error("OpenAI API 调用失败: HTTP {} - {}", wce.getStatusCode().value(), wce.getMessage());
                    } else {
                        log.error("流式响应出错", e);
                    }
                    LogQueryTool.clearCookie();
                    LogQueryTool.clearNamespaceName();
                    LogQueryTool.clearContainerName();
                })
                .doOnComplete(() -> {
                    log.info("流式响应完成");
                    LogQueryTool.clearCookie();
                    LogQueryTool.clearNamespaceName();
                    LogQueryTool.clearContainerName();
                });
    }
}

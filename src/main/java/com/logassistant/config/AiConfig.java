package com.logassistant.config;

import com.logassistant.tool.LogQueryTool;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class AiConfig {

  @Value("${spring.ai.openai.base-url:not-set}")
  private String baseUrl;

  @Value("${spring.ai.openai.api-key:not-set}")
  private String apiKey;

  @Value("${spring.ai.openai.chat.options.model:not-set}")
  private String model;

  @PostConstruct
  public void logConfig() {
    String maskedKey = apiKey.length() > 8
        ? apiKey.substring(0, 5) + "***" + apiKey.substring(apiKey.length() - 3)
        : "***";
    log.info("========== AI 配置 ==========");
    log.info("Base URL: {}", baseUrl);
    log.info("API Key:  {}", maskedKey);
    log.info("Model:    {}", model);
    log.info("=============================");
  }

  private static final String SYSTEM_PROMPT = """
      你是一个智能日志查询助手，专门帮助用户查询和分析应用日志。

      你的核心能力：
      1. 理解用户的自然语言查询意图，提取关键词、时间范围等查询条件
      2. 调用 searchLogs 工具查询 Kibana 中的日志
      3. 分析和总结查询到的日志内容，给出有价值的洞察

      使用规则：
      - 当用户想查询日志时，调用 searchLogs 工具
      - 时间格式使用 ISO 8601（如 2026-02-13T00:00:00Z）
      - 如果用户说"最近1小时"、"今天"、"昨天"等相对时间，请根据当前时间计算具体时间范围。当前时间会在用户消息中提供。
      - 查询结果返回后，请对日志进行总结分析，包括：
        * 错误数量和类型统计
        * 主要问题描述
        * 可能的原因分析
        * 建议的排查方向
      - 如果用户没提供索引名，使用默认索引
      - 如果用户问的不是日志相关问题，正常回答即可
      """;

  @Bean
  public ChatClient chatClient(ChatClient.Builder builder, LogQueryTool logQueryTool) {
    return builder
        .defaultSystem(SYSTEM_PROMPT)
        .defaultTools(logQueryTool)
        .build();
  }
}

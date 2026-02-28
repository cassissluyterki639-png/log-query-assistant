package com.logassistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class KibanaService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Value("${kibana.default-index}")
    private String defaultIndex;

    @Value("${kibana.kbn-version}")
    private String kbnVersion;

    public KibanaService(@Value("${kibana.base-url}") String kibanaBaseUrl, ObjectMapper objectMapper) {
        this.restClient = RestClient.builder()
                .baseUrl(kibanaBaseUrl)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * 查询 Kibana 日志
     */
    public String searchLogs(String keyword, String startTime, String endTime,
            String index, String cookie, int size, String namespaceName, String containerName) {
        String targetIndex = (index != null && !index.isBlank()) ? index : defaultIndex;
        int targetSize = size > 0 ? Math.min(size, 500) : 100;

        try {
            String requestBody = buildSearchRequestBody(keyword, startTime, endTime, targetIndex, targetSize,
                    namespaceName, containerName);
            log.info("Kibana 查询请求: index={}, keyword={}, timeRange=[{} ~ {}], size={}",
                    targetIndex, keyword, startTime, endTime, targetSize);
            log.debug("Kibana 请求体: {}", requestBody);

            String response = restClient.post()
                    .uri("/internal/bsearch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Cookie", cookie)
                    .header("kbn-version", kbnVersion)
                    .header("kbn-xsrf", "true")
                    .header("kbn-system-api", "true")
                    .header("Connection", "keep-alive")
                    .body(requestBody)
                    .retrieve()
                    .onStatus(status -> status.isError(), (req, res) -> {
                        String errMsg = String.format("Kibana API 返回错误码: %d %s", res.getStatusCode().value(),
                                res.getStatusText());
                        log.error(errMsg);
                        throw new RuntimeException(errMsg);
                    })
                    .body(String.class);

            if (response == null) {
                return "查询失败：Kibana 返回了空响应。";
            }

            log.debug("Kibana 原始响应: {}", response);
            return parseSearchResponse(response);
        } catch (Exception e) {
            log.error("Kibana 查询失败", e);
            return "查询失败: " + e.getMessage();
        }
    }

    private String buildSearchRequestBody(String keyword, String startTime, String endTime,
            String index, int size, String namespaceName, String containerName) throws Exception {
        // 处理时间范围
        if (startTime == null || startTime.isBlank()) {
            // 完全没指定时间，默认最近24小时
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
            endTime = now.format(DateTimeFormatter.ISO_INSTANT);
            startTime = now.minusHours(24).format(DateTimeFormatter.ISO_INSTANT);
        } else if (endTime == null || endTime.isBlank()) {
            // 有开始时间但没结束时间，默认到当前时间
            endTime = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ISO_INSTANT);
        }

        // 构建查询 JSON
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode batch = objectMapper.createArrayNode();
        ObjectNode batchItem = objectMapper.createObjectNode();

        // request
        ObjectNode request = objectMapper.createObjectNode();
        ObjectNode params = objectMapper.createObjectNode();
        params.put("index", index);

        // body
        ObjectNode body = objectMapper.createObjectNode();
        body.put("size", size);

        // sort
        ArrayNode sort = objectMapper.createArrayNode();
        ObjectNode sortItem = objectMapper.createObjectNode();
        ObjectNode timestampSort = objectMapper.createObjectNode();
        timestampSort.put("order", "desc");
        timestampSort.put("unmapped_type", "boolean");
        sortItem.set("@timestamp", timestampSort);
        sort.add(sortItem);
        body.set("sort", sort);

        body.put("version", true);

        // fields
        ArrayNode fields = objectMapper.createArrayNode();
        ObjectNode allFields = objectMapper.createObjectNode();
        allFields.put("field", "*");
        allFields.put("include_unmapped", "true");
        fields.add(allFields);
        ObjectNode timestampField = objectMapper.createObjectNode();
        timestampField.put("field", "@timestamp");
        timestampField.put("format", "strict_date_optional_time");
        fields.add(timestampField);
        body.set("fields", fields);

        body.set("script_fields", objectMapper.createObjectNode());
        body.putArray("stored_fields").add("*");
        body.set("runtime_mappings", objectMapper.createObjectNode());
        body.put("_source", false);

        // query - bool
        ObjectNode query = objectMapper.createObjectNode();
        ObjectNode bool = objectMapper.createObjectNode();
        bool.putArray("must");
        bool.putArray("should");
        bool.putArray("must_not");

        // filter
        ArrayNode filter = objectMapper.createArrayNode();

        // keyword filter (multi_match) - 跳过 * 通配符，multi_match 不支持
        if (keyword != null && !keyword.isBlank() && !"*".equals(keyword.trim())) {
            // 支持多个关键词，用空格分隔
            String[] keywords = keyword.split("\\s+");
            for (String kw : keywords) {
                ObjectNode filterItem = objectMapper.createObjectNode();
                ObjectNode boolFilter = objectMapper.createObjectNode();
                ArrayNode boolFilterArray = objectMapper.createArrayNode();
                ObjectNode multiMatch = objectMapper.createObjectNode();
                ObjectNode multiMatchInner = objectMapper.createObjectNode();
                multiMatchInner.put("type", "phrase");
                multiMatchInner.put("query", kw.trim());
                multiMatchInner.put("lenient", true);
                multiMatch.set("multi_match", multiMatchInner);
                boolFilterArray.add(multiMatch);
                boolFilter.set("filter", boolFilterArray);
                filterItem.set("bool", boolFilter);
                filter.add(filterItem);
            }
        }

        // time range filter
        ObjectNode rangeFilter = objectMapper.createObjectNode();
        ObjectNode range = objectMapper.createObjectNode();
        ObjectNode timestampRange = objectMapper.createObjectNode();
        timestampRange.put("gte", startTime);
        timestampRange.put("lte", endTime);
        timestampRange.put("format", "strict_date_optional_time");
        range.set("@timestamp", timestampRange);
        rangeFilter.set("range", range);
        filter.add(rangeFilter);

        // namespace filter
        if (namespaceName != null && !namespaceName.isBlank()) {
            ObjectNode matchFilter = objectMapper.createObjectNode();
            matchFilter.set("match",
                    objectMapper.createObjectNode().put("kubernetes.namespace_name", namespaceName.trim()));
            filter.add(matchFilter);
        }

        // container filter
        if (containerName != null && !containerName.isBlank()) {
            ObjectNode matchFilter = objectMapper.createObjectNode();
            matchFilter.set("match",
                    objectMapper.createObjectNode().put("kubernetes.container_name", containerName.trim()));
            filter.add(matchFilter);
        }

        bool.set("filter", filter);
        query.set("bool", bool);
        body.set("query", query);

        // highlight
        ObjectNode highlight = objectMapper.createObjectNode();
        highlight.putArray("pre_tags").add("@kibana-highlighted-field@");
        highlight.putArray("post_tags").add("@/kibana-highlighted-field@");
        highlight.set("fields", objectMapper.createObjectNode().set("*", objectMapper.createObjectNode()));
        highlight.put("fragment_size", 2147483647);
        body.set("highlight", highlight);

        params.set("body", body);
        request.set("params", params);
        batchItem.set("request", request);

        // options
        ObjectNode options = objectMapper.createObjectNode();
        options.put("strategy", "ese");
        options.put("isRestore", false);
        options.put("isStored", false);
        batchItem.set("options", options);

        batch.add(batchItem);

        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("batch", batch);

        return objectMapper.writeValueAsString(wrapper);
    }

    private String parseSearchResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);

            // 尝试解析 bsearch 响应格式
            JsonNode hits;
            if (root.isArray() && root.size() > 0) {
                hits = root.get(0).path("result").path("rawResponse").path("hits").path("hits");
            } else {
                hits = root.path("rawResponse").path("hits").path("hits");
                if (hits.isMissingNode()) {
                    hits = root.path("result").path("rawResponse").path("hits").path("hits");
                }
            }

            if (hits.isMissingNode() || !hits.isArray() || hits.isEmpty()) {
                return "未找到匹配的日志记录。";
            }

            List<String> logEntries = new ArrayList<>();
            int count = 0;
            for (JsonNode hit : hits) {
                if (count >= 50)
                    break; // 限制返回给 AI 的条数，避免 token 过多

                JsonNode fields = hit.path("fields");
                StringBuilder entry = new StringBuilder();
                entry.append("[").append(count + 1).append("] ");

                // 提取 @timestamp
                JsonNode timestamp = fields.path("@timestamp");
                if (timestamp.isArray() && timestamp.size() > 0) {
                    entry.append("时间: ").append(timestamp.get(0).asText()).append(" | ");
                }

                // 提取 message 字段
                JsonNode message = fields.path("message");
                if (message.isArray() && message.size() > 0) {
                    String msg = message.get(0).asText();
                    if (msg.length() > 500) {
                        msg = msg.substring(0, 500) + "...";
                    }
                    entry.append("内容: ").append(msg);
                }

                // 提取 level 字段
                JsonNode level = fields.path("level");
                if (level.isMissingNode())
                    level = fields.path("log.level");
                if (level.isArray() && level.size() > 0) {
                    entry.append(" | 级别: ").append(level.get(0).asText());
                }

                logEntries.add(entry.toString());
                count++;
            }

            // 获取总命中数
            JsonNode totalHits;
            if (root.isArray() && root.size() > 0) {
                totalHits = root.get(0).path("result").path("rawResponse").path("hits").path("total");
            } else {
                totalHits = root.path("rawResponse").path("hits").path("total");
                if (totalHits.isMissingNode()) {
                    totalHits = root.path("result").path("rawResponse").path("hits").path("total");
                }
            }

            long total = totalHits.isObject() ? totalHits.path("value").asLong() : totalHits.asLong(count);

            StringBuilder result = new StringBuilder();
            result.append("共找到 ").append(total).append(" 条日志记录");
            if (total > 50) {
                result.append("（以下展示最近 50 条）");
            }
            result.append("：\n\n");
            result.append(String.join("\n\n", logEntries));

            return result.toString();
        } catch (Exception e) {
            log.error("解析 Kibana 响应失败", e);
            return "解析日志结果失败: " + e.getMessage() + "\n原始响应: "
                    + (response != null && response.length() > 1000 ? response.substring(0, 1000) : response);
        }
    }
}

package com.atguigu.lease.web.app.assistant.service.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RedisAssistantLongTermMemoryService implements AssistantLongTermMemoryService {

    private static final String KEY_PREFIX = "assistant:memory:profile:";
    private static final long PROFILE_TTL_DAYS = 90L;

    private static final String KEY_BUDGET = "budget";
    private static final String KEY_DISTRICT = "district";
    private static final String KEY_MOVE_IN = "moveIn";
    private static final String KEY_PAYMENT = "payment";

    private static final Pattern BUDGET_RANGE_PATTERN = Pattern.compile("(\\d{3,5})\\s*(?:到|至|[-~])\\s*(\\d{3,5})");
    private static final Pattern BUDGET_UPPER_PATTERN = Pattern.compile("(\\d{3,5})\\s*(?:以内|以下|左右)");
    private static final Pattern MOVE_IN_PATTERN = Pattern.compile("(今天|明天|后天|下周[一二三四五六日天]?|\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}|\\d{1,2}月\\d{1,2}日)");

    private static final List<String> DISTRICT_KEYWORDS = List.of(
            "朝阳区", "海淀区", "昌平区", "通州区", "丰台区", "大兴区"
    );
    private static final List<String> PAYMENT_KEYWORDS = List.of(
            "月付", "季付", "半年付", "年付", "押一付一", "押一付三", "押二付一"
    );

    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public RedisAssistantLongTermMemoryService(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void rememberUserMessage(Long userId, String userMessage) {
        if (userId == null || !StringUtils.hasText(userMessage)) {
            return;
        }

        Map<String, String> profile = load(userId);
        boolean changed = merge(profile, userMessage.trim());
        if (!changed) {
            return;
        }
        save(userId, profile);
    }

    @Override
    public String buildMemoryPrompt(Long userId) {
        if (userId == null) {
            return "";
        }

        Map<String, String> profile = load(userId);
        List<String> lines = new ArrayList<>();

        addLine(profile, KEY_BUDGET, "预算偏好：", lines);
        addLine(profile, KEY_DISTRICT, "区域偏好：", lines);
        addLine(profile, KEY_MOVE_IN, "入住时间偏好：", lines);
        addLine(profile, KEY_PAYMENT, "支付偏好：", lines);

        if (lines.isEmpty()) {
            return "";
        }

        return """
                以下是用户长期偏好记忆（仅作参考，本轮有明确新要求时以本轮为准）：
                - %s
                """.formatted(String.join("\n- ", lines));
    }

    private boolean merge(Map<String, String> profile, String userMessage) {
        boolean changed = false;
        String normalized = userMessage.replace(" ", "");

        String budget = extractBudget(normalized);
        if (budget != null) {
            changed |= set(profile, KEY_BUDGET, budget);
        }

        String district = findAny(normalized, DISTRICT_KEYWORDS);
        if (district != null) {
            changed |= set(profile, KEY_DISTRICT, district);
        }

        String payment = findAny(normalized, PAYMENT_KEYWORDS);
        if (payment != null) {
            changed |= set(profile, KEY_PAYMENT, payment);
        }

        String moveIn = extractMoveIn(normalized);
        if (moveIn != null) {
            changed |= set(profile, KEY_MOVE_IN, moveIn);
        }

        return changed;
    }

    private String extractBudget(String message) {
        Matcher rangeMatcher = BUDGET_RANGE_PATTERN.matcher(message);
        if (rangeMatcher.find()) {
            int a = safeParseInt(rangeMatcher.group(1));
            int b = safeParseInt(rangeMatcher.group(2));
            if (isBudget(a) && isBudget(b)) {
                int min = Math.min(a, b);
                int max = Math.max(a, b);
                return min + "-" + max + "元/月";
            }
        }

        Matcher upperMatcher = BUDGET_UPPER_PATTERN.matcher(message);
        if (upperMatcher.find()) {
            int upper = safeParseInt(upperMatcher.group(1));
            if (isBudget(upper)) {
                return "不高于" + upper + "元/月";
            }
        }
        return null;
    }

    private String extractMoveIn(String message) {
        if (!(message.contains("入住") || message.contains("起租") || message.contains("搬入"))) {
            return null;
        }
        Matcher matcher = MOVE_IN_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String findAny(String message, List<String> keywords) {
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                return keyword;
            }
        }
        return null;
    }

    private Map<String, String> load(Long userId) {
        String raw = stringRedisTemplate.opsForValue().get(key(userId));
        if (!StringUtils.hasText(raw)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, String> map = objectMapper.readValue(raw, MAP_TYPE);
            return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private void save(Long userId, Map<String, String> profile) {
        try {
            String value = objectMapper.writeValueAsString(profile);
            stringRedisTemplate.opsForValue().set(key(userId), value, PROFILE_TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception ignored) {
        }
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    private boolean set(Map<String, String> profile, String key, String value) {
        String old = profile.put(key, value);
        return old == null || !old.equals(value);
    }

    private void addLine(Map<String, String> profile, String key, String prefix, List<String> lines) {
        String value = profile.get(key);
        if (StringUtils.hasText(value)) {
            lines.add(prefix + value);
        }
    }

    private int safeParseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private boolean isBudget(int value) {
        return value >= 500 && value <= 50000;
    }
}

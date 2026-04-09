package com.atguigu.lease.web.app.chat.agent;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class AssistantTaskStateStore {

    private final ConcurrentMap<String, AssistantTaskState> states = new ConcurrentHashMap<>();

    public AssistantTaskState get(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return null;
        }
        return states.get(conversationId.trim());
    }

    public void save(String conversationId, AssistantTaskState state) {
        if (!StringUtils.hasText(conversationId) || state == null) {
            return;
        }
        states.put(conversationId.trim(), state);
    }

    public void clear(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return;
        }
        states.remove(conversationId.trim());
    }
}

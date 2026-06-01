package com.atguigu.lease.web.app.assistant.service.agent;

import java.util.ArrayList;
import java.util.List;

public class SupervisorPlan {

    private String primaryAgent;
    private List<String> additionalAgents = new ArrayList<>();
    private String goal;
    private String reason;
    private boolean needsClarification;
    private String clarificationQuestion;

    public String getPrimaryAgent() {
        return primaryAgent;
    }

    public void setPrimaryAgent(String primaryAgent) {
        this.primaryAgent = primaryAgent;
    }

    public List<String> getAdditionalAgents() {
        return additionalAgents;
    }

    public void setAdditionalAgents(List<String> additionalAgents) {
        this.additionalAgents = additionalAgents == null ? new ArrayList<>() : new ArrayList<>(additionalAgents);
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public boolean isNeedsClarification() {
        return needsClarification;
    }

    public void setNeedsClarification(boolean needsClarification) {
        this.needsClarification = needsClarification;
    }

    public String getClarificationQuestion() {
        return clarificationQuestion;
    }

    public void setClarificationQuestion(String clarificationQuestion) {
        this.clarificationQuestion = clarificationQuestion;
    }
}

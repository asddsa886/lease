package com.atguigu.lease.web.ops.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.atguigu.lease.web.ops.service.assistant.DisabledOpsAssistantService;
import com.atguigu.lease.web.ops.service.assistant.MultiAgentOpsAssistantService;
import com.atguigu.lease.web.ops.service.assistant.OpsAssistantService;
import com.atguigu.lease.web.ops.service.log.OpsLogScanService;
import com.atguigu.lease.web.ops.service.session.OpsAssistantSessionService;
import com.atguigu.lease.web.ops.service.tool.OpsLogAnalysisTools;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

@Configuration
@EnableConfigurationProperties({OpsAssistantProperties.class, OpsLogScanProperties.class, OpsHistoryProperties.class})
public class OpsAssistantConfiguration {

    @Bean("opsAppAgent")
    @ConditionalOnBean(ChatModel.class)
    public ReactAgent opsAppAgent(ChatModel chatModel,
                                  OpsLogAnalysisTools tools,
                                  @Qualifier("opsAppSkillRegistry") SkillRegistry skillRegistry) {
        return buildSpecialistAgent(
                "ops-app-agent",
                "负责启动失败、异常栈、Bean 注入失败、业务异常、OOM、线程池问题。",
                chatModel,
                tools,
                skillRegistry,
                """
                        你是应用异常分析 Agent。
                        你的职责是分析启动失败、异常栈、Bean 装配失败、业务异常、OOM 和线程池问题。
                        优先说明最可能根因、关键证据，以及下一步排查顺序。
                        """
        );
    }

    @Bean("opsInfraAgent")
    @ConditionalOnBean(ChatModel.class)
    public ReactAgent opsInfraAgent(ChatModel chatModel,
                                    OpsLogAnalysisTools tools,
                                    @Qualifier("opsInfraSkillRegistry") SkillRegistry skillRegistry) {
        return buildSpecialistAgent(
                "ops-infra-agent",
                "负责 Redis、RabbitMQ、MySQL、MinIO、Milvus、网络连通性和配置缺失问题。",
                chatModel,
                tools,
                skillRegistry,
                """
                        你是依赖与基础设施分析 Agent。
                        你的职责是分析 Redis、RabbitMQ、MySQL、MinIO、Milvus、网络连通性和配置异常。
                        回答时要明确指出是哪一个依赖出了问题，以及它更像连接失败、认证失败还是超时。
                        """
        );
    }

    @Bean("opsPerformanceAgent")
    @ConditionalOnBean(ChatModel.class)
    public ReactAgent opsPerformanceAgent(ChatModel chatModel,
                                          OpsLogAnalysisTools tools,
                                          @Qualifier("opsPerformanceSkillRegistry") SkillRegistry skillRegistry) {
        return buildSpecialistAgent(
                "ops-performance-agent",
                "负责慢 SQL、高耗时请求、数据库超时、连接池耗尽和锁等待问题。",
                chatModel,
                tools,
                skillRegistry,
                """
                        你是性能与数据库分析 Agent。
                        你的职责是分析慢 SQL、高耗时请求、数据库超时、连接池耗尽和锁等待问题。
                        回答时优先给出瓶颈位置和证据，不要只做空泛猜测。
                        """
        );
    }

    @Bean("opsSupervisorAgent")
    @ConditionalOnBean(ChatModel.class)
    public SupervisorAgent opsSupervisorAgent(ChatModel chatModel,
                                              OpsLogAnalysisTools tools,
                                              @Qualifier("opsSupervisorSkillRegistry") SkillRegistry supervisorSkillRegistry,
                                              @Qualifier("opsAppAgent") ReactAgent opsAppAgent,
                                              @Qualifier("opsInfraAgent") ReactAgent opsInfraAgent,
                                              @Qualifier("opsPerformanceAgent") ReactAgent opsPerformanceAgent) {
        ReactAgent mainAgent = ReactAgent.builder()
                .name("ops-supervisor-main")
                .description("负责判断问题属于哪个 specialist，并整合最终回答。")
                .model(chatModel)
                .methodTools(tools)
                .hooks(SkillsAgentHook.builder().skillRegistry(supervisorSkillRegistry).build())
                .systemPrompt("""
                        你是运维日志分析主管 Agent。
                        你要根据用户问题判断应该查看当前扫描、历史故障，还是两者都看。
                        如果当前没有扫描结果，优先调用 runLogScan 完成最近窗口扫描。
                        如果用户问的是“昨天那次”“最近几次”，优先使用历史工具。
                        最终输出必须包含：最可能根因、关键证据、排查建议、操作建议。
                        """)
                .instruction("""
                        除非信息已经足够，否则不要跳过工具查询。
                        优先回答对可用性影响最大的根因。
                        当问题已经落在某个 specialist 领域时，交给最匹配的 specialist 继续分析。
                        """)
                .build();

        return SupervisorAgent.builder()
                .name("ops-supervisor")
                .description("统一编排运维日志分析任务。")
                .model(chatModel)
                .mainAgent(mainAgent)
                .subAgents(List.of(opsAppAgent, opsInfraAgent, opsPerformanceAgent))
                .systemPrompt("你负责协调多个 specialist 完成日志问题定位。")
                .instruction("根据用户问题选择最合适的 specialist，必要时多轮分析，直到输出完整结论。")
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(OpsAssistantService.class)
    public OpsAssistantService opsAssistantService(ObjectProvider<SupervisorAgent> supervisorAgentProvider,
                                                   OpsAssistantProperties assistantProperties,
                                                   OpsLogScanService logScanService,
                                                   OpsAssistantSessionService sessionService) {
        SupervisorAgent supervisorAgent = supervisorAgentProvider.getIfAvailable();
        if (assistantProperties.isEnabled() && supervisorAgent != null) {
            return new MultiAgentOpsAssistantService(supervisorAgent, assistantProperties, logScanService, sessionService);
        }
        return new DisabledOpsAssistantService();
    }

    private ReactAgent buildSpecialistAgent(String name,
                                            String description,
                                            ChatModel chatModel,
                                            OpsLogAnalysisTools tools,
                                            SkillRegistry skillRegistry,
                                            String systemPrompt) {
        return ReactAgent.builder()
                .name(name)
                .description(description)
                .model(chatModel)
                .methodTools(tools)
                .hooks(SkillsAgentHook.builder().skillRegistry(skillRegistry).build())
                .systemPrompt(systemPrompt)
                .instruction("严格依据技能规则、工具结果和日志证据回答问题。")
                .build();
    }
}

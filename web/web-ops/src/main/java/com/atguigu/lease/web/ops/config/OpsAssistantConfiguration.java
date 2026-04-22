package com.atguigu.lease.web.ops.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.atguigu.lease.web.ops.service.assistant.MultiAgentOpsAssistantService;
import com.atguigu.lease.web.ops.service.assistant.OpsAssistantConstants;
import com.atguigu.lease.web.ops.service.assistant.OpsAssistantService;
import com.atguigu.lease.web.ops.service.log.OpsLogScanService;
import com.atguigu.lease.web.ops.service.session.OpsAssistantSessionService;
import com.atguigu.lease.web.ops.service.tool.OpsLogAnalysisTools;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@ConditionalOnProperty(prefix = "lease.ops.assistant", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({OpsAssistantProperties.class, OpsLogScanProperties.class, OpsHistoryProperties.class})
public class OpsAssistantConfiguration {

    @Bean("opsAppAgent")
    public ReactAgent opsAppAgent(ChatModel chatModel,
                                  OpsLogAnalysisTools tools,
                                  @Qualifier("opsAppSkillRegistry") SkillRegistry skillRegistry) {
        return buildSpecialistAgent(
                OpsAssistantConstants.APP_AGENT_NAME,
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
    public ReactAgent opsInfraAgent(ChatModel chatModel,
                                    OpsLogAnalysisTools tools,
                                    @Qualifier("opsInfraSkillRegistry") SkillRegistry skillRegistry) {
        return buildSpecialistAgent(
                OpsAssistantConstants.INFRA_AGENT_NAME,
                "负责 Redis、RabbitMQ、MySQL、MinIO、Milvus、网络连通性和配置缺失问题。",
                chatModel,
                tools,
                skillRegistry,
                """
                        你是依赖与基础设施分析 Agent。
                        你的职责是分析 Redis、RabbitMQ、MySQL、MinIO、Milvus、网络连通性和配置异常。
                        回答时要明确指出是哪个依赖出了问题，以及它更像连接失败、认证失败还是超时。
                        """
        );
    }

    @Bean("opsPerformanceAgent")
    public ReactAgent opsPerformanceAgent(ChatModel chatModel,
                                          OpsLogAnalysisTools tools,
                                          @Qualifier("opsPerformanceSkillRegistry") SkillRegistry skillRegistry) {
        return buildSpecialistAgent(
                OpsAssistantConstants.PERFORMANCE_AGENT_NAME,
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
    public SupervisorAgent opsSupervisorAgent(ChatModel chatModel,
                                              @Qualifier("opsSupervisorSkillRegistry") SkillRegistry skillRegistry,
                                              @Qualifier("opsAppAgent") ReactAgent opsAppAgent,
                                              @Qualifier("opsInfraAgent") ReactAgent opsInfraAgent,
                                              @Qualifier("opsPerformanceAgent") ReactAgent opsPerformanceAgent) {
        ReactAgent mainAgent = ReactAgent.builder()
                .name(OpsAssistantConstants.ROUTER_AGENT_NAME)
                .description("只负责选择下一步 specialist 的路由 Agent，不直接回答用户。")
                .model(chatModel)
                .hooks(SkillsAgentHook.builder().skillRegistry(skillRegistry).build())
                .systemPrompt("""
                        你是运维日志分析路由 Agent。
                        你不直接回答用户，也不输出分析结论，只负责决定下一步交给哪个 specialist。

                        可选 specialist 只有 3 个：
                        - ops-app-agent：处理启动失败、异常栈、Bean 注入失败、空指针、OOM、线程池拒绝等应用异常
                        - ops-infra-agent：处理 Redis、RabbitMQ、MySQL、MinIO、Milvus、网络、认证、配置缺失等依赖问题
                        - ops-performance-agent：处理慢 SQL、高耗时请求、数据库超时、连接池耗尽、锁等待等性能问题

                        决策规则：
                        - 每一轮最多只能选择 1 个 specialist。
                        - 如果某个 specialist 已经给出了足够完整的最终答复，返回 FINISH。
                        - 如果还需要继续分析，就返回最匹配的 specialist 名称。
                        - 不要调用工具，不要输出解释，不要输出自然语言答案。

                        输出格式要求：
                        - 只能输出 JSON 数组字符串。
                        - 合法示例：["ops-app-agent"]、["ops-infra-agent"]、["ops-performance-agent"]、["FINISH"]
                        - 禁止输出 Markdown、代码块、额外标点、解释说明或任何自然语言。
                        """)
                .instruction("""
                        严格按 JSON 数组输出。
                        除了 ["ops-app-agent"]、["ops-infra-agent"]、["ops-performance-agent"]、["FINISH"] 之外，不要输出任何其他内容。
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
    public OpsAssistantService opsAssistantService(SupervisorAgent supervisorAgent,
                                                   OpsAssistantProperties assistantProperties,
                                                   OpsLogScanService logScanService,
                                                   OpsAssistantSessionService sessionService) {
        return new MultiAgentOpsAssistantService(supervisorAgent, assistantProperties, logScanService, sessionService);
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
                .outputKey(OpsAssistantConstants.SPECIALIST_REPLY_KEY)
                .hooks(SkillsAgentHook.builder().skillRegistry(skillRegistry).build())
                .systemPrompt(systemPrompt)
                .instruction("严格依据技能规则、工具结果和日志证据回答问题。")
                .build();
    }
}

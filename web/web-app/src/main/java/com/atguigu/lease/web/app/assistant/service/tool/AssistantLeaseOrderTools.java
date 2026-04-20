package com.atguigu.lease.web.app.assistant.service.tool;

import com.atguigu.lease.web.app.service.LeaseOrderService;
import com.atguigu.lease.web.app.vo.order.LeaseOrderDetailVo;
import com.atguigu.lease.web.app.vo.order.LeaseOrderSubmitVo;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class AssistantLeaseOrderTools extends AbstractAssistantTools {

    private final LeaseOrderService leaseOrderService;

    public AssistantLeaseOrderTools(LeaseOrderService leaseOrderService) {
        this.leaseOrderService = leaseOrderService;
    }

    @Tool(description = "查询当前用户的签约订单列表")
    public AssistantToolResult listMyLeaseOrders(ToolContext toolContext) {
        return executeTool("listMyLeaseOrders", toolContext, "签约订单列表查询成功",
                () -> leaseOrderService.listItemByCurrentUser(currentUserId(toolContext)));
    }

    @Tool(description = "根据订单ID查询当前用户的签约订单详情")
    public AssistantToolResult getLeaseOrderDetail(@ToolParam(description = "订单ID", required = true) Long orderId,
                                                   ToolContext toolContext) {
        return executeTool("getLeaseOrderDetail", toolContext, "签约订单详情查询成功",
                () -> leaseOrderService.getDetailById(orderId, currentUserId(toolContext)));
    }

    @Tool(description = "为当前用户创建签约订单")
    public AssistantToolResult createLeaseOrder(@ToolParam(description = "房间ID", required = true) Long roomId,
                                                @ToolParam(description = "租期ID", required = true) Long leaseTermId,
                                                @ToolParam(description = "支付方式ID", required = true) Long paymentTypeId,
                                                @ToolParam(description = "起租日期，按中国时区本地日期理解。支持 yyyy-MM-dd，也支持“明天”“下周一”这类自然语言。", required = true) String leaseStartDate,
                                                @ToolParam(description = "补充说明") String additionalInfo,
                                                ToolContext toolContext) {
        return executeTool("createLeaseOrder", toolContext, "签约订单创建成功", () -> {
            Long userId = currentUserId(toolContext);
            LeaseOrderSubmitVo submitVo = new LeaseOrderSubmitVo();
            submitVo.setRoomId(roomId);
            submitVo.setLeaseTermId(leaseTermId);
            submitVo.setPaymentTypeId(paymentTypeId);
            submitVo.setLeaseStartDate(parseDate(leaseStartDate));
            submitVo.setAdditionalInfo(additionalInfo);

            Long orderId = leaseOrderService.submit(submitVo, userId);
            LeaseOrderDetailVo detail = leaseOrderService.getDetailById(orderId, userId);
            return detail;
        });
    }

    @Tool(description = "取消当前用户的待处理签约订单")
    public AssistantToolResult cancelLeaseOrder(@ToolParam(description = "订单ID", required = true) Long orderId,
                                                ToolContext toolContext) {
        return executeTool("cancelLeaseOrder", toolContext, "签约订单取消成功", () -> {
            Long userId = currentUserId(toolContext);
            leaseOrderService.cancelById(orderId, userId);
            return leaseOrderService.getDetailById(orderId, userId);
        });
    }
}

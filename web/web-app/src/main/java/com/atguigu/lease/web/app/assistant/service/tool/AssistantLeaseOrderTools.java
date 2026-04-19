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

    @Tool(description = "List lease orders of current user.")
    public AssistantToolResult listMyLeaseOrders(ToolContext toolContext) {
        return executeTool("listMyLeaseOrders", toolContext, "签约订单列表查询成功",
                () -> leaseOrderService.listItemByCurrentUser(currentUserId(toolContext)));
    }

    @Tool(description = "Get lease order detail by order id for current user.")
    public AssistantToolResult getLeaseOrderDetail(@ToolParam(description = "Order id", required = true) Long orderId,
                                                   ToolContext toolContext) {
        return executeTool("getLeaseOrderDetail", toolContext, "签约订单详情查询成功",
                () -> leaseOrderService.getDetailById(orderId, currentUserId(toolContext)));
    }

    @Tool(description = "Create a lease order for current user.")
    public AssistantToolResult createLeaseOrder(@ToolParam(description = "Room id", required = true) Long roomId,
                                                @ToolParam(description = "Lease term id", required = true) Long leaseTermId,
                                                @ToolParam(description = "Payment type id", required = true) Long paymentTypeId,
                                                @ToolParam(description = "Lease start date in yyyy-MM-dd format", required = true) String leaseStartDate,
                                                @ToolParam(description = "Additional note") String additionalInfo,
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

    @Tool(description = "Cancel a pending lease order of current user.")
    public AssistantToolResult cancelLeaseOrder(@ToolParam(description = "Order id", required = true) Long orderId,
                                                ToolContext toolContext) {
        return executeTool("cancelLeaseOrder", toolContext, "签约订单取消成功", () -> {
            Long userId = currentUserId(toolContext);
            leaseOrderService.cancelById(orderId, userId);
            return leaseOrderService.getDetailById(orderId, userId);
        });
    }
}

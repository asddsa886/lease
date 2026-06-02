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

    @Tool(description = "Query the current user's lease order list.")
    public AssistantToolResult listMyLeaseOrders(ToolContext toolContext) {
        return executeTool("listMyLeaseOrders", toolContext, "签约订单列表查询成功",
                () -> leaseOrderService.listItemByCurrentUser(currentUserId(toolContext)));
    }

    @Tool(description = "Query the current user's lease order detail by order id.")
    public AssistantToolResult getLeaseOrderDetail(@ToolParam(description = "Order id", required = true) Long orderId,
                                                   ToolContext toolContext) {
        return executeTool("getLeaseOrderDetail", toolContext, "签约订单详情查询成功",
                () -> leaseOrderService.getDetailById(orderId, currentUserId(toolContext)));
    }

    @Tool(description = "Create a lease order for the current user. Must ask for natural-language confirmation first, then call with confirmed=true.")
    public AssistantToolResult createLeaseOrder(@ToolParam(description = "Room id", required = true) Long roomId,
                                                @ToolParam(description = "Lease term id", required = true) Long leaseTermId,
                                                @ToolParam(description = "Payment type id", required = true) Long paymentTypeId,
                                                @ToolParam(description = "Lease start date in China local date, yyyy-MM-dd", required = true) String leaseStartDate,
                                                @ToolParam(description = "Additional notes") String additionalInfo,
                                                @ToolParam(description = "Only true after the user clearly confirms this write action in natural language", required = true) Boolean confirmed,
                                                ToolContext toolContext) {
        if (!Boolean.TRUE.equals(confirmed)) {
            return AssistantToolResult.fail("请先让用户明确确认创建签约订单，再调用 createLeaseOrder");
        }
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

    @Tool(description = "Cancel the current user's pending lease order. Must ask for natural-language confirmation first, then call with confirmed=true.")
    public AssistantToolResult cancelLeaseOrder(@ToolParam(description = "Order id", required = true) Long orderId,
                                                @ToolParam(description = "Only true after the user clearly confirms this write action in natural language", required = true) Boolean confirmed,
                                                ToolContext toolContext) {
        if (!Boolean.TRUE.equals(confirmed)) {
            return AssistantToolResult.fail("请先让用户明确确认取消签约订单，再调用 cancelLeaseOrder");
        }
        return executeTool("cancelLeaseOrder", toolContext, "签约订单取消成功", () -> {
            Long userId = currentUserId(toolContext);
            leaseOrderService.cancelById(orderId, userId);
            return leaseOrderService.getDetailById(orderId, userId);
        });
    }
}

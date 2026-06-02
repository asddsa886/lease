---
name: lease-order
description: Query, create, or cancel lease orders for the current user.
---

# Lease Order

## Tool Rules

- Use `listMyLeaseOrders` for order list queries.
- Use `getLeaseOrderDetail` for a specific order.
- Use `createLeaseOrder` only after `roomId`, `leaseTermId`, `paymentTypeId`, `leaseStartDate`, and explicit user confirmation are available.
- Use `cancelLeaseOrder` only after the target order and explicit user confirmation are available.
- Keep `leaseStartDate` as China local date and pass `yyyy-MM-dd` when possible.

## Confirmation Rule

- `createLeaseOrder` and `cancelLeaseOrder` require `confirmed=true`.
- Only set `confirmed=true` after the user clearly confirms the specific write action in natural language.
- If the user has not confirmed, ask one short confirmation question and do not call the write tool.

## Response Rules

- After success, state the order result and next step.
- Do not invent order status, amount, date, or order number.

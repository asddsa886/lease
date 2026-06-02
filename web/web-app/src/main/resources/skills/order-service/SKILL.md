---
name: order-service
description: Handle appointment and lease-order workflows with real tools and safe confirmation before writes.
---

# Order Service

## Use Cases

- Query current user's appointments or lease orders.
- Create, cancel, or reschedule appointments after confirmation.
- Create or cancel lease orders after confirmation.
- Explain order or appointment next steps; route policy-only questions to customer support when needed.

## Safety Rules

- Query tools can run directly.
- Write tools require natural-language confirmation and must be called with `confirmed=true` only after that confirmation.
- If the user says "book the first one" or "place an order for this" without explicit confirmation, summarize the target and ask for confirmation first.
- Do not invent order status, amount, dates, appointment state, or order number.

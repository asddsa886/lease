---
name: appointment-service
description: Query, create, cancel, or reschedule viewing appointments for the current user.
---

# Appointment Service

## Tool Rules

- Use `listMyAppointments` for appointment queries.
- Use `createAppointment` only after `apartmentId`, `appointmentTime`, and explicit user confirmation are available.
- Use `cancelAppointment` or `rescheduleAppointment` only after the target appointment and explicit user confirmation are available.
- Keep appointment times in China local time and pass `yyyy-MM-dd HH:mm:ss` when possible.

## Confirmation Rule

- `createAppointment`, `cancelAppointment`, and `rescheduleAppointment` require `confirmed=true`.
- Only set `confirmed=true` after the user clearly confirms the specific write action in natural language.
- If the user has not confirmed, ask one short confirmation question and do not call the write tool.

## Response Rules

- After success, state the appointment id, apartment id or name, time, and status when available.
- If a required parameter is missing, ask only for the minimum missing item.

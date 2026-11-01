# TRYST Notification Architecture

## 1. Overview
The TRYST notification system provides asynchronous, persistent user notifications that survive browser disconnections and application restarts.

## 2. Notification Model & Storage (`NotificationRepository`)
- **Storage**: SQLite `notifications` table.
- **Attributes**: `id`, `user_id`, `type`, `title`, `message`, `reference_type`, `reference_id`, `is_read`, `created_at`.
- **User Scoping**: Notifications are strictly isolated by `user_id`. Users can only access and acknowledge their own notifications.
- **Foreign Key**: Cascade deletion on user deletion.

## 3. Endpoints & UX
- `GET /api/notifications`: Retrieve recent unread and read notifications with count.
- `POST /api/notifications/read-all`: Mark all notifications as acknowledged.
- Automatic notifications emitted upon:
  - Task completion or failure.
  - Review readiness.
  - Review approval or rejection.
  - Git commit execution.

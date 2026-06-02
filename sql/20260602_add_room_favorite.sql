CREATE TABLE IF NOT EXISTS `room_favorite` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT 'primary key',
  `user_id` bigint NOT NULL COMMENT 'user id',
  `room_id` bigint NOT NULL COMMENT 'room id',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'create time',
  `update_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'update time',
  `is_deleted` tinyint NOT NULL DEFAULT 0 COMMENT 'logic delete',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_room_favorite_user_room_deleted` (`user_id`, `room_id`, `is_deleted`) USING BTREE,
  KEY `idx_room_favorite_room` (`room_id`) USING BTREE,
  KEY `idx_room_favorite_user_time` (`user_id`, `create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='APP room favorite';

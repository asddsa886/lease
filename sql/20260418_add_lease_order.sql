SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS `lease_order`;
CREATE TABLE `lease_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'lease order id',
  `order_no` varchar(32) NOT NULL COMMENT 'order no',
  `user_id` bigint NOT NULL COMMENT 'user id',
  `phone` varchar(11) DEFAULT NULL COMMENT 'user phone',
  `name` varchar(50) DEFAULT NULL COMMENT 'user name',
  `apartment_id` bigint NOT NULL COMMENT 'apartment id',
  `room_id` bigint NOT NULL COMMENT 'room id',
  `lease_start_date` date DEFAULT NULL COMMENT 'lease start date',
  `lease_end_date` date DEFAULT NULL COMMENT 'lease end date',
  `lease_term_id` bigint NOT NULL COMMENT 'lease term id',
  `rent` decimal(16,2) DEFAULT NULL COMMENT 'rent per month',
  `deposit` decimal(16,2) DEFAULT NULL COMMENT 'deposit',
  `payment_type_id` bigint NOT NULL COMMENT 'payment type id',
  `status` tinyint DEFAULT NULL COMMENT '1 pending payment, 2 paid, 3 confirmed, 4 canceled, 5 timeout canceled',
  `additional_info` varchar(255) DEFAULT NULL COMMENT 'additional info',
  `expire_time` timestamp NULL DEFAULT NULL COMMENT 'expire time',
  `agreement_id` bigint DEFAULT NULL COMMENT 'linked agreement id',
  `create_time` timestamp NULL DEFAULT NULL COMMENT 'create time',
  `update_time` timestamp NULL DEFAULT NULL COMMENT 'update time',
  `is_deleted` tinyint DEFAULT 0 COMMENT 'logical delete flag',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_lease_order_order_no` (`order_no`) USING BTREE,
  KEY `idx_lease_order_user_status` (`user_id`, `status`) USING BTREE,
  KEY `idx_lease_order_room_status` (`room_id`, `status`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='lease order table';

SET FOREIGN_KEY_CHECKS = 1;

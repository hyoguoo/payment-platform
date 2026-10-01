-- Snapshot from isolated reproduction database; execute in a separate empty schema.
CREATE TABLE `pg_inbox` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `order_id` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` enum('PENDING','IN_PROGRESS','APPROVED','FAILED','QUARANTINED') COLLATE utf8mb4_unicode_ci NOT NULL,
  `amount` bigint NOT NULL,
  `stored_status_result` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `reason_code` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `payment_key` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '벤더 결제 키 (PENDING INSERT 시 기록)',
  `vendor_type` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '벤더 타입 (e.g., TOSS_PAYMENTS)',
  `stored_traceparent` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `attempt` int NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `ux_pg_inbox_order_id` (`order_id`),
  KEY `idx_pg_inbox_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=500001 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

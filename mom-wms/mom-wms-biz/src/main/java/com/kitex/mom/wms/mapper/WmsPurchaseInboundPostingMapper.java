package com.kitex.mom.wms.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 按实际租户、工厂及原入库单读写采购入库永久投递台账。完成和失败都要求持有同一轮租约。
 */
@Repository
@RequiredArgsConstructor
public class WmsPurchaseInboundPostingMapper {

	private final JdbcTemplate db;

	/** 仅在累计升级已绑定启用的移动过账与来源冻结守卫后允许新 PDA 单进入 V1。 */
	public boolean mobileGuardReady() {
		return Boolean.TRUE.equals(db.queryForObject("""
				SELECT count(*) = 2
				FROM pg_catalog.pg_trigger t
				JOIN (VALUES
				    (to_regclass('mom_wms.wms_in_bill'), 'trg_purchase_posting_version_413',
				        to_regprocedure('mom_wms.guard_purchase_posting_version_414()'), 23),
				    (to_regclass('mom_wms.wms_purchase_inbound_posting'), 'trg_purchase_inbound_posting_413',
				        to_regprocedure('mom_wms.guard_purchase_inbound_posting_414()'), 31)
				) expected(relation, name, function, events)
				    ON t.tgrelid = expected.relation AND t.tgname = expected.name
				    AND t.tgfoid = expected.function AND t.tgtype = expected.events
				WHERE t.tgenabled = 'O' AND NOT t.tgisinternal AND t.tgqual IS NULL AND t.tgnargs = 0
				""", Boolean.class));
	}

	/** 在入库事务内登记冻结载荷。已存在时由调用方核原文指纹。 */
	public int insert(Long tenant, String factory, Long billId, Long receiptId, String fingerprint, String payload) {
		return db.update("""
				INSERT INTO mom_wms.wms_purchase_inbound_posting
				    (tenant_id, factory_code, wms_bill_id, purchase_receipt_id, fingerprint, payload, status)
				VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
				ON CONFLICT (tenant_id, factory_code, wms_bill_id) DO NOTHING
				""", tenant, factory, billId, receiptId, fingerprint, payload);
	}

	/** 读取固定组织及原单的投递记录，未登记返回空。 */
	public Posting find(Long tenant, String factory, Long billId) {
		return db.query("""
				SELECT purchase_receipt_id, fingerprint, payload, attempt_count, last_error, delivered_at,
				    CASE WHEN status = 'SENDING' AND lease_until <= clock_timestamp()
				        THEN 'RETRY' ELSE status END AS status
				FROM mom_wms.wms_purchase_inbound_posting
				WHERE tenant_id = ? AND factory_code = ? AND wms_bill_id = ?
				""", WmsPurchaseInboundPostingMapper::mapPosting, tenant, factory, billId).stream().findFirst().orElse(null);
	}

	/** 原子认领一次投递，服务中断后可在原租约到期后重新认领同一原单。 */
	public int claim(Long tenant, String factory, Long billId, UUID token) {
		return db.update("""
				UPDATE mom_wms.wms_purchase_inbound_posting
				SET status = 'SENDING', claim_token = ?, lease_until = clock_timestamp() + interval '2 minutes',
				    attempt_count = attempt_count + 1, last_error = NULL
				WHERE tenant_id = ? AND factory_code = ? AND wms_bill_id = ?
				  AND (status IN ('PENDING', 'RETRY') OR (status = 'SENDING' AND lease_until <= clock_timestamp()))
				""", token, tenant, factory, billId);
	}

	/** 仅本轮持有者可保存已核验的 PMS 成功结果。 */
	public int delivered(Long tenant, String factory, Long billId, UUID token) {
		return db.update("""
				UPDATE mom_wms.wms_purchase_inbound_posting
				SET status = 'DELIVERED', claim_token = NULL, lease_until = NULL, last_error = NULL,
				    delivered_at = clock_timestamp()
				WHERE tenant_id = ? AND factory_code = ? AND wms_bill_id = ?
				  AND status = 'SENDING' AND claim_token = ?
				""", tenant, factory, billId, token);
	}

	/** 仅本轮持有者可登记失败，保留原载荷和累计尝试次数。 */
	public int failed(Long tenant, String factory, Long billId, UUID token, String error) {
		return db.update("""
				UPDATE mom_wms.wms_purchase_inbound_posting
				SET status = 'RETRY', claim_token = NULL, lease_until = NULL, last_error = ?
				WHERE tenant_id = ? AND factory_code = ? AND wms_bill_id = ?
				  AND status = 'SENDING' AND claim_token = ?
				""", error, tenant, factory, billId, token);
	}

	private static Posting mapPosting(ResultSet row, int index) throws SQLException {
		return new Posting(row.getLong("purchase_receipt_id"), row.getString("fingerprint"),
				row.getString("payload"), row.getString("status"), row.getInt("attempt_count"),
				row.getString("last_error"), row.getObject("delivered_at", LocalDateTime.class));
	}

	/**
	 * 原单投递快照，载荷只在服务内部使用。
	 *
	 * @param receiptId PMS原收料单主键
	 * @param fingerprint 冻结事实摘要
	 * @param payload 冻结原文
	 * @param status 当前投递状态
	 * @param attemptCount 累计认领次数
	 * @param lastError 最近一次受控失败提示
	 * @param deliveredAt 永久回执确认时间
	 */
	public record Posting(Long receiptId, String fingerprint, String payload, String status, int attemptCount,
	                      String lastError, LocalDateTime deliveredAt) {
	}
}

package com.kitex.mom.pms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.pms.dto.PmsPurchaseInboundPosting;
import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

/**
 * 正式采购入库回写的精确来源、数量及永久收件记录。各语句显式校验真实组织。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface PmsPurchaseInboundPostingMapper {

	/**
	 * 同租户、同工厂、同 WMS 入库单只允许一个事务首次受理。
	 */
	@Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(#{key}, 0))")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	Integer lockEvent(@Param("key") String key);

	/** 按来源原号读取已经提交的永久回执。 */
	@Select("""
			SELECT wms_bill_id, purchase_receipt_id, fingerprint, line_count
			FROM mom_pms.pms_wms_purchase_inbound_receipt
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND wms_bill_id=#{billId}
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	PmsPurchaseInboundPosting.Receipt receipt(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                          @Param("billId") Long billId);

	/** 与采购业务写入一同提交的永久受理事实。 */
	@Insert("""
			INSERT INTO mom_pms.pms_wms_purchase_inbound_receipt
				(tenant_id, factory_code, wms_bill_id, purchase_receipt_id, fingerprint, line_count, payload)
			VALUES (#{tenant}, #{factory}, #{receipt.wmsBillId}, #{receipt.purchaseReceiptId},
				#{receipt.fingerprint}, #{receipt.lineCount}, CAST(#{payload} AS jsonb))
			""")
	int saveReceipt(@Param("tenant") Long tenant, @Param("factory") String factory,
	                @Param("receipt") PmsPurchaseInboundPosting.Receipt receipt, @Param("payload") String payload);

	/** 收料子表以实际父单限制工厂。旧入库累计为空表示尚无该类累计。 */
	@Select("""
			SELECT d.id, d.order_detail_id, d.mat_code, d.mat_unit, d.mat_batch, d.quality_num,
				COALESCE(d.in_num, 0) AS in_num, d.qualified_gen, d.un_qualified_gen, d.state
			FROM mom_pms.wms_purchase_receipt_batch_detail d
			JOIN mom_pms.wms_purchase_receipt r ON r.id=d.bill_id AND r.tenant_id=d.tenant_id
			WHERE d.tenant_id=#{tenant} AND r.factory_code=#{factory} AND r.id=#{receiptId}
				AND d.id=#{batchId} AND d.del_flag='0' AND r.del_flag='0'
			FOR UPDATE OF d
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	Batch lockBatch(@Param("tenant") Long tenant, @Param("factory") String factory,
	                @Param("receiptId") Long receiptId, @Param("batchId") Long batchId);

	/** 同一实际收料单内按原订单行和物料精确读取，重复来源拒绝首行匹配。 */
	@Select("""
			SELECT d.id, d.mat_unit, d.actual_num, d.wms_status, COALESCE(d.in_num, 0) AS in_num
			FROM mom_pms.wms_purchase_receipt_detail d
			JOIN mom_pms.wms_purchase_receipt r ON r.id=d.bill_id AND r.tenant_id=d.tenant_id
			WHERE d.tenant_id=#{tenant} AND r.factory_code=#{factory} AND r.id=#{receiptId}
				AND d.order_detail_id=#{orderDetailId} AND d.mat_code=#{matCode}
				AND d.del_flag='0' AND r.del_flag='0'
			ORDER BY d.id LIMIT 2 FOR UPDATE OF d
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	List<ReceiptLine> lockReceiptLines(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                   @Param("receiptId") Long receiptId, @Param("orderDetailId") Long orderDetailId,
	                                   @Param("matCode") String matCode);

	/** 按已锁定批次写入精确的标准单位累计和实际完成状态。 */
	@Update("""
			UPDATE mom_pms.wms_purchase_receipt_batch_detail d
			SET in_num=#{total}, state=#{state}, update_by=#{actor}, update_time=LOCALTIMESTAMP
			WHERE d.tenant_id=#{tenant} AND d.bill_id=#{receiptId} AND d.id=#{batchId} AND d.del_flag='0'
				AND COALESCE(d.in_num, 0)=#{before}
				AND EXISTS (SELECT 1 FROM mom_pms.wms_purchase_receipt r WHERE r.id=d.bill_id
					AND r.tenant_id=d.tenant_id AND r.factory_code=#{factory} AND r.del_flag='0')
			""")
	int updateBatch(@Param("tenant") Long tenant, @Param("factory") String factory,
	                @Param("receiptId") Long receiptId, @Param("batchId") Long batchId,
	                @Param("before") BigDecimal before, @Param("total") BigDecimal total,
	                @Param("state") String state, @Param("actor") String actor);

	/** 仅更新锁定原收料行的本次实际累计。 */
	@Update("""
			UPDATE mom_pms.wms_purchase_receipt_detail d
			SET in_num=#{total}, update_by=#{actor}, update_time=LOCALTIMESTAMP
			WHERE d.tenant_id=#{tenant} AND d.bill_id=#{receiptId} AND d.id=#{lineId}
				AND d.del_flag='0' AND d.wms_status=true AND COALESCE(d.in_num, 0)=#{before}
				AND EXISTS (SELECT 1 FROM mom_pms.wms_purchase_receipt r WHERE r.id=d.bill_id
					AND r.tenant_id=d.tenant_id AND r.factory_code=#{factory} AND r.del_flag='0')
			""")
	int updateReceiptLine(@Param("tenant") Long tenant, @Param("factory") String factory,
	                      @Param("receiptId") Long receiptId, @Param("lineId") Long lineId,
	                      @Param("before") BigDecimal before, @Param("total") BigDecimal total,
	                      @Param("actor") String actor);

	/** 精确读取订单行权威上限和累计，避免从 Double 推断数据库金额或数量。 */
	@Select("""
			SELECT COALESCE(arrival_num, 0) AS arrival_num, order_num, COALESCE(cancelled_num, 0) AS cancelled_num
			FROM mom_pms.pms_order_detail
			WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND pms_order_id=#{orderId}
				AND id=#{lineId} AND del_flag='0' FOR UPDATE
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	OrderQuantity orderQuantity(@Param("tenant") Long tenant, @Param("factory") String factory,
	                            @Param("orderId") Long orderId, @Param("lineId") Long lineId);

	/** 计划号只能绑定当前工厂的一条原计划。受理阶段可锁定，防止判定后变成外部下发。 */
	@Select("""
			<script>
			SELECT order_no, COALESCE(plan_arrival_num, 0) AS arrival_num
			FROM mom_pms.pms_plan WHERE tenant_id=#{tenant} AND factory_code=#{factory}
				AND pms_plan_no=#{planNo} AND del_flag='0' ORDER BY id LIMIT 2
			<if test="lock"> FOR UPDATE </if>
			</script>
			""")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	List<Plan> plan(@Param("tenant") Long tenant, @Param("factory") String factory,
	                @Param("planNo") String planNo, @Param("lock") boolean lock);

	/** 原质量批次与库存标准单位累计。 */
	@Data
	class Batch {
		private Long id;
		private Long orderDetailId;
		private String matCode;
		private String matUnit;
		private String matBatch;
		private BigDecimal qualityNum;
		private BigDecimal inNum;
		private String qualifiedGen;
		private String unQualifiedGen;
		private String state;
	}

	/** 原收料行及标准单位累计。 */
	@Data
	class ReceiptLine {
		private Long id;
		private String matUnit;
		private BigDecimal actualNum;
		private Boolean wmsStatus;
		private BigDecimal inNum;
	}

	/** 原采购订单行的标准单位到货、订购、撤回数量。 */
	@Data
	class OrderQuantity {
		private BigDecimal arrivalNum;
		private BigDecimal orderNum;
		private BigDecimal cancelledNum;
	}

	/** PMS 本地计划及可选外部计划下发号。 */
	@Data
	class Plan {
		private String orderNo;
		private BigDecimal arrivalNum;
	}
}

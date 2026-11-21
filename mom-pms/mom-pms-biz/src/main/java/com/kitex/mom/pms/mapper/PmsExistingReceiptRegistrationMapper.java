package com.kitex.mom.pms.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.kitex.mom.pms.entity.WmsPurchaseReceiptEntity;
import com.kitex.mom.pms.entity.WmsPurchaseReceiptDetailEntity;
import com.kitex.mom.pms.entity.WmsPurchaseReceiptBatchDetailEntity;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 现单实收登记的精确取证与条件写入。
 * SQL显式绑定已核验租户、实际工厂和原单；数量直接映射BigDecimal，避免经历史Double实体丢失精度。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface PmsExistingReceiptRegistrationMapper {

	/**
	 * 按真实组织及完整原号最多读取两单，拒绝重复编码；加锁重读不复用前次缓存。
	 */
	@Select("<script>SELECT * FROM mom_pms.wms_purchase_receipt "
			+ "WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND bill_no=#{billNo} AND del_flag='0' "
			+ "ORDER BY id LIMIT 2 <if test='lock'>FOR UPDATE</if></script>")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	List<WmsPurchaseReceiptEntity> bills(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                     @Param("billNo") String billNo, @Param("lock") boolean lock);

	/**
	 * 当前原单明细沿旧响应实体返回；无工厂列的子表通过真实父单限定工厂。
	 */
	@Select("SELECT d.* FROM mom_pms.wms_purchase_receipt_detail d "
			+ "JOIN mom_pms.wms_purchase_receipt r ON r.id=d.bill_id AND r.tenant_id=d.tenant_id "
			+ "WHERE d.tenant_id=#{tenant} AND r.factory_code=#{factory} AND r.id=#{billId} "
			+ "AND d.del_flag='0' AND r.del_flag='0' ORDER BY d.id")
	@Options(useCache = false)
	List<WmsPurchaseReceiptDetailEntity> details(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                             @Param("billId") Long billId);

	/**
	 * 质量批次只读取同一真实父单的记录，不按物料编码跨单拼接。
	 */
	@Select("SELECT d.* FROM mom_pms.wms_purchase_receipt_batch_detail d "
			+ "JOIN mom_pms.wms_purchase_receipt r ON r.id=d.bill_id AND r.tenant_id=d.tenant_id "
			+ "WHERE d.tenant_id=#{tenant} AND r.factory_code=#{factory} AND r.id=#{billId} "
			+ "AND d.del_flag='0' AND r.del_flag='0' ORDER BY d.id")
	@Options(useCache = false)
	List<WmsPurchaseReceiptBatchDetailEntity> batches(@Param("tenant") Long tenant, @Param("factory") String factory,
	                                                  @Param("billId") Long billId);

	/**
	 * 原单锁后锁定精确原行；旧客户端仅在物料匹配唯一时可省略明细ID。
	 */
	@Select("<script>SELECT d.id,d.bill_id AS billId,d.order_detail_id AS orderDetailId,d.mat_code AS matCode,d.mat_unit AS matUnit,"
			+ "d.should_num AS shouldQuantity,d.actual_num AS actualQuantity,d.mat_batch AS matBatch,"
			+ "d.mat_produce_date AS produceDate,d.price,d.wms_status AS wmsStatus "
			+ "FROM mom_pms.wms_purchase_receipt_detail d WHERE d.tenant_id=#{tenant} "
			+ "AND d.bill_id=#{billId} AND d.mat_code=#{matCode} AND d.del_flag='0' "
			+ "AND EXISTS(SELECT 1 FROM mom_pms.wms_purchase_receipt r WHERE r.id=d.bill_id "
			+ "AND r.tenant_id=d.tenant_id AND r.factory_code=#{factory} AND r.del_flag='0') "
			+ "<if test='detailId != null'>AND d.id=#{detailId} </if>ORDER BY d.id LIMIT 2 FOR UPDATE OF d</script>")
	@Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
	List<Line> lines(@Param("tenant") Long tenant, @Param("factory") String factory,
	                 @Param("billId") Long billId, @Param("matCode") String matCode, @Param("detailId") Long detailId);

	/**
	 * 已进入质量分批的原行不得通过总量接口继续覆盖实物批次。
	 */
	@Select("<script>SELECT count(*) FROM mom_pms.wms_purchase_receipt_batch_detail d "
			+ "WHERE d.tenant_id=#{tenant} AND d.bill_id=#{billId} AND d.mat_code=#{matCode} AND d.del_flag='0' "
			+ "AND EXISTS(SELECT 1 FROM mom_pms.wms_purchase_receipt r WHERE r.id=d.bill_id "
			+ "AND r.tenant_id=d.tenant_id AND r.factory_code=#{factory} AND r.del_flag='0') "
			+ "<if test='orderDetailId != null'>AND d.order_detail_id=#{orderDetailId}</if></script>")
	long batchCount(@Param("tenant") Long tenant, @Param("factory") String factory,
	                @Param("billId") Long billId, @Param("matCode") String matCode,
	                @Param("orderDetailId") Long orderDetailId);

	/**
	 * 按锁定原量条件登记，保留已有价格；仅允许权威订单行补齐原先未知的价格。
	 */
	@Update("UPDATE mom_pms.wms_purchase_receipt_detail d SET actual_num=#{received},mat_batch=#{batch},"
			+ "mat_produce_date=#{date},price=COALESCE(d.price,#{price,jdbcType=NUMERIC}),"
			+ "update_by=#{actor},update_time=LOCALTIMESTAMP "
			+ "WHERE d.tenant_id=#{tenant} AND d.bill_id=#{billId} AND d.id=#{line.id} "
			+ "AND d.mat_code=#{line.matCode} AND d.actual_num=#{line.actualQuantity} "
			+ "AND d.should_num=#{line.shouldQuantity} AND d.wms_status=false AND d.del_flag='0' "
			+ "AND EXISTS(SELECT 1 FROM mom_pms.wms_purchase_receipt r WHERE r.id=d.bill_id "
			+ "AND r.tenant_id=d.tenant_id AND r.factory_code=#{factory} AND r.del_flag='0')")
	int receive(@Param("tenant") Long tenant, @Param("factory") String factory, @Param("billId") Long billId,
	            @Param("line") Line line, @Param("received") BigDecimal received, @Param("batch") String batch,
	            @Param("date") LocalDate date, @Param("price") BigDecimal price, @Param("actor") String actor);

	/**
	 * 首次实收仅从待处理推进处理中，必须精确更新一行。
	 */
	@Update("UPDATE mom_pms.wms_purchase_receipt SET state='1',update_by=#{actor},update_time=LOCALTIMESTAMP "
			+ "WHERE tenant_id=#{tenant} AND factory_code=#{factory} AND id=#{billId} AND state='0' AND del_flag='0'")
	int begin(@Param("tenant") Long tenant, @Param("factory") String factory,
	          @Param("billId") Long billId, @Param("actor") String actor);

	/**
	 * 仅供本登记事务使用的原收料行精确数量与实物快照，不作为客户端可回写实体。
	 */
	@Data
	class Line {
		/**
		 * 原收料明细主键。
		 */
		private Long id;
		/**
		 * 原收料单主键。
		 */
		private Long billId;
		/**
		 * 所属原采购订单行；手工无订单收料可空。
		 */
		private Long orderDetailId;
		/**
		 * 原物料编码。
		 */
		private String matCode;

		/** 原收料行的采购计量单位，用于对照被锁定的原订单明细。 */
		private String matUnit;
		/**
		 * 原应收量，物料基本单位，numeric(24,6)。
		 */
		private BigDecimal shouldQuantity;
		/**
		 * 已登记实收量，物料基本单位；未知值不能补零。
		 */
		private BigDecimal actualQuantity;
		/**
		 * 已实收批次；首次登记前可空。
		 */
		private String matBatch;
		/**
		 * 已实收批次生产日期；首次登记前可空。
		 */
		private LocalDate produceDate;
		/**
		 * 已保存的权威单价；未知时可空。
		 */
		private BigDecimal price;
		/**
		 * 原行是否已进入后续仓储处理；只有明确false允许续收。
		 */
		private Boolean wmsStatus;
	}
}

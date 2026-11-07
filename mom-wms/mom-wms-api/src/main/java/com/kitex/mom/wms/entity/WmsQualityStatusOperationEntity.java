package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 质量库存隔离、处置和实物执行账。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("wms_quality_status_operation")
@EqualsAndHashCode(callSuper = true)
public class WmsQualityStatusOperationEntity extends BaseEntity<WmsQualityStatusOperationEntity> {
	/**
	 * 本次分类完整实物来源JSONB；仅领域JDBC显式cast写入，禁止MP以VARCHAR回写。
	 */
	@TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
	private String physicalSourceJson;
	/**
	 * 普通成功定向HOLD的原处置父ID，其他操作为空。
	 */
	private Long reholdOfPhysicalOperationId;
	/**
	 * 特殊退回质量接管的版本化Binding原文；普通及历史操作为空，冻结后不可修改。
	 */
	private String specialReturnBindingJson;
	/**
	 * 新特殊接管事务在原仓锁后由数据库分配的永久办理序号；普通及历史为空，不用雪花ID推断顺序。
	 */
	@TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
	private Long specialReturnSequence;
	/**
	 * 租户内稳定幂等请求号，重试必须保持原号及原命令。
	 */
	private String requestId;
	/**
	 * 正式质量隔离单号，处置及重隔离保持首次QH。
	 */
	private String holdNo;
	/**
	 * 正式质量来源类型，QMS案件固定QMS_NCR。
	 */
	private String sourceType;
	/**
	 * 正式来源案件ID文本，不是特殊申请或库存ID。
	 */
	private String sourceId;
	/**
	 * 质量动作：HOLD、RELEASE、CONCESSION、REWORK、SCRAP或RETURN。
	 */
	private String action;
	/**
	 * 冻结物料编码。
	 */
	private String matCode;
	/**
	 * 冻结批次，可为空。
	 */
	private String matBatch;
	/**
	 * 冻结数量的库存单位，防止同物料批次跨单位合并。
	 */
	private String matUnit;
	/**
	 * 冻结供应商编码，可为空。
	 */
	private String supplierCode;
	/**
	 * 实际库存仓库编码，特殊原件必须与冻结目标相同。
	 */
	private String warehouseCode;
	/**
	 * 冻结库位编码，可为空。
	 */
	private String siteCode;
	/**
	 * 冻结容器编码，可为空。
	 */
	private String containerCode;
	/**
	 * 原请求基本库存单位数量。
	 */
	private BigDecimal requestedQuantity;
	/**
	 * 已实际影响基本库存单位数量。
	 */
	private BigDecimal affectedQuantity;
	/**
	 * 实际影响库存行ID，逗号分隔，不按物料批次推测。
	 */
	private String affectedStockIds;
	/**
	 * 本次处置已完整记录拆分；历史事务默认false，不能用于整行无余量证明。
	 */
	private Boolean splitRecorded;
	/**
	 * 本次处置新建的余量库存行ID，逗号分隔；无拆分时为空。
	 */
	private String splitRemainderStockIds;
	/**
	 * 原库存行、余量库存行及拆分数量的同事务映射；历史事务不补造。
	 */
	private String splitLineageJson;
	/**
	 * 实际完成库存拆分/状态写入的时间，不随仓库后续执行更新。
	 */
	private LocalDateTime splitRecordedAt;
	/**
	 * 操作结果：PENDING、SUCCESS、ACTION_PENDING或EXECUTED。
	 */
	private String status;
	/**
	 * 本次质量动作业务原因。
	 */
	private String reason;
	/**
	 * 永久业务结果说明。
	 */
	private String resultMessage;
	/**
	 * 仓库实物执行稳定请求号。
	 */
	private String executionRequestId;
	/**
	 * 实物报废或退货执行单号。
	 */
	private String executionRef;
	/**
	 * 仓库实物执行说明。
	 */
	private String executionRemark;
	/**
	 * 实际仓库执行账号快照。
	 */
	private String executedBy;
	/**
	 * 实际仓库执行人姓名快照。
	 */
	private String executedByName;
	/**
	 * 实物报废或退货确认时间。
	 */
	private LocalDateTime executedTime;
	/**
	 * 当前实际工厂编码，必须通过人员工厂及原仓授权。
	 */
	@TableField(fill = FieldFill.INSERT)
	private String factoryCode;
}

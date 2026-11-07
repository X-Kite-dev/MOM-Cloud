package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 普通派工永久原号原件；受理或取消后不变，不承载生产实际数量。
 */
@Data
@TenantTable
@TableName("mes_dispatch_command")
@EqualsAndHashCode(callSuper = true)
public class MesDispatchCommandEntity extends BaseEntity<MesDispatchCommandEntity> {
	/** 实名授权的实际工厂编码。 */
	@Schema(description = "实名授权的实际工厂编码")
	private String factoryCode;

	/** 稳定原命令号，实际租户工厂内唯一。 */
	@Schema(description = "稳定原命令号，实际租户工厂内唯一")
	private String requestId;

	/** 完整规范原文的SHA256；实际组织及操作人另以不可变列精确核对。 */
	@Schema(description = "完整规范原文的SHA256；实际组织及操作人另以不可变列精确核对")
	private String requestFingerprint;

	/** 提交或永久取消原命令的实名操作人主键。 */
	@Schema(description = "提交或永久取消原命令的实名操作人主键")
	private Long actorId;

	/** 原操作人工号快照，不保存令牌。 */
	@Schema(description = "原操作人工号快照，不保存令牌")
	private String actorCode;

	/** 原生产工单编码。 */
	@Schema(description = "原生产工单编码")
	private String taskCode;

	/** 永久ACCEPTED已受理或CANCELLED未受理作废。 */
	@Schema(description = "永久ACCEPTED已受理或CANCELLED未受理作废")
	private String status;

	/** 完整规范原命令正文；受理、取消与迟到查证均使用此原文。 */
	@Schema(description = "完整规范原命令正文；受理、取消与迟到查证均使用此原文")
	private String requestJson;

	/** 完整逐行真实派工回执；取消为无派工结果。 */
	@Schema(description = "完整逐行真实派工回执；取消为无派工结果")
	private String resultJson;

	/** 未受理原号永久作废的原因；ACCEPTED时为空。 */
	@Schema(description = "未受理原号永久作废原因；ACCEPTED时为空")
	private String cancelReason;
}

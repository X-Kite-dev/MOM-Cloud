package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 普通PLAN工单真实接收时的不可变标准工时来源；原标准与批准标准均按首次接收冻结。
 */
@Data
@TenantTable
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName(value = "mes_plan_labor_adoption", schema = "mom_mes")
public class MesPlanLaborAdoptionEntity extends BaseEntity<MesPlanLaborAdoptionEntity> {

	/**
	 * 实际接收派工所属的真实工厂编码。
	 */
	@Schema(description = "实际接收派工所属的真实工厂编码。")
	private String factoryCode;

	/**
	 * 原普通PLAN工单编码。
	 */
	@Schema(description = "原普通PLAN工单编码。")
	private String taskCode;

	/**
	 * 原MES工单真实主键。
	 */
	@Schema(description = "原MES工单真实主键。")
	private Long taskId;

	/**
	 * 原稳定节点实际工序任务主键。
	 */
	@Schema(description = "原稳定节点实际工序任务主键。")
	private Long processId;

	/**
	 * 原稳定路线节点RN-id，不能以重复工序编码代替。
	 */
	@Schema(description = "原稳定路线节点RN-id，不能以重复工序编码代替。")
	private String routeNodeCode;

	/**
	 * 同事务实际首次接收的原派工主键，同组织仅冻结一次。
	 */
	@Schema(description = "同事务实际首次接收的原派工主键，同组织仅冻结一次。")
	private Long arrangeId;

	/**
	 * 实际采用的APPLIED永久决策主键；采用原标准时为空。
	 */
	@Schema(description = "实际采用的APPLIED永久决策主键；采用原标准时为空。")
	private Long decisionId;

	/**
	 * 原工单工程联合包UTF8 SHA256，不被批准层改写。
	 */
	@Schema(description = "原工单工程联合包UTF8 SHA256，不被批准层改写。")
	private String originalEngineeringHash;

	/**
	 * 实际采用来源的正式工程发布主键。
	 */
	@Schema(description = "实际采用来源的正式工程发布主键。")
	private Long releaseId;

	/**
	 * 实际采用来源的完整工程包SHA256。
	 */
	@Schema(description = "实际采用来源的完整工程包SHA256。")
	private String contentHash;

	/**
	 * 实际采用标准工时封套原件；原包无标准时为空。
	 */
	@Schema(description = "实际采用标准工时封套原件；原包无标准时为空。")
	private String laborSnapshot;

	/**
	 * 标准工时sourceSnapshot原文SHA256；原包无标准时为空。
	 */
	@Schema(description = "标准工时sourceSnapshot原文SHA256；原包无标准时为空。")
	private String sourceFingerprint;

	/**
	 * 与真实首次接收同事务冻结来源的当地业务时间。
	 */
	@Schema(description = "与真实首次接收同事务冻结来源的当地业务时间。")
	private LocalDateTime receivedAt;
}

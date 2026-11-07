package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 每条真实新派工的出生配额原件；终止仅结算占用，冲销不得恢复配额。
 */
@Data
@TenantTable
@TableName("mes_dispatch_allocation")
@EqualsAndHashCode(callSuper = true)
public class MesDispatchAllocationEntity extends BaseEntity<MesDispatchAllocationEntity> {
	/** 实名授权的实际工厂编码。 */
	@Schema(description = "实名授权的实际工厂编码")
	private String factoryCode;

	/** 出生所属的永久ACCEPTED原命令主键。 */
	@Schema(description = "出生所属的永久ACCEPTED原命令主键")
	private Long commandId;

	/** 命令内稳定唯一分配行标识。 */
	@Schema(description = "命令内稳定唯一分配行标识")
	private String allocationKey;

	/** 冻结真实工单主键。 */
	@Schema(description = "冻结真实工单主键")
	private Long taskId;

	/** 冻结真实工单编码。 */
	@Schema(description = "冻结真实工单编码")
	private String taskCode;

	/** 冻结真实工序任务主键。 */
	@Schema(description = "冻结真实工序任务主键")
	private Long produceTaskId;

	/** 冻结真实工序任务编码。 */
	@Schema(description = "冻结真实工序任务编码")
	private String produceTaskCode;

	/** 冻结真实工程路线节点标识。 */
	@Schema(description = "冻结真实工程路线节点标识")
	private String sourceRouteNodeCode;

	/** 沿原工单冻结的精确工程版本主键。 */
	@Schema(description = "沿原工单冻结的精确工程版本主键")
	private Long engineeringReleaseId;

	/** 沿原工单冻结的工程内容SHA256。 */
	@Schema(description = "沿原工单冻结的工程内容SHA256")
	private String engineeringContentHash;

	/** 同事务出生的真实派工主键，不允许给历史派工补造配额。 */
	@Schema(description = "同事务出生的真实派工主键，不允许给历史派工补造配额")
	private Long arrangeId;

	/** 同事务出生的真实派工编码。 */
	@Schema(description = "同事务出生的真实派工编码")
	private String arrangeCode;

	/** 出生正配额，单位沿原工序物料单位，不承载GOOD或NG实绩。 */
	@Schema(description = "出生正配额，单位沿原工序物料单位，不承载GOOD或NG实绩")
	private BigDecimal allocatedQuantity;

	/** 当前永久占用；初始配额，合法终止一次结算后不随冲销减小。 */
	@Schema(description = "当前永久占用；初始配额，合法终止一次结算后不随冲销减小")
	private BigDecimal occupiedQuantity;

	/** RESERVED占满、FINISHED终止后永久占原配额、UNEXECUTED_CLOSED未执行关闭释放。 */
	@Schema(description = "RESERVED占满、FINISHED终止后永久占原配额、UNEXECUTED_CLOSED未执行关闭释放")
	private String settlementStatus;

	/** 合法终止完成一次结算的业务时间；RESERVED时为空。 */
	@Schema(description = "合法终止完成一次结算的业务时间；RESERVED时为空")
	private LocalDateTime settledTime;
}

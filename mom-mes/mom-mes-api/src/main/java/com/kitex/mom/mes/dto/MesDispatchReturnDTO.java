package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesDispatchReturnOperationEntity;
import com.kitex.mom.mes.entity.MesDispatchReturnRequestEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesToolingExecutionEntity;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.List;

/**
 * 已接收未开工派工的申请、审批和证据查看契约。
 */
public final class MesDispatchReturnDTO {
	private MesDispatchReturnDTO() {
	}

	@Data
	@Schema(description = "提交已接收未开工退派申请")
	public static class SubmitCommand {
		/** 派工主键。 */
		@Schema(description = "派工主键", requiredMode = Schema.RequiredMode.REQUIRED)
		private Long arrangeId;
		/** 16至64位永久幂等请求号。 */
		@Schema(description = "16至64位永久幂等请求号", requiredMode = Schema.RequiredMode.REQUIRED)
		private String requestId;
		/** 预览取得的64位事实指纹。 */
		@Schema(description = "预览取得的64位事实指纹", requiredMode = Schema.RequiredMode.REQUIRED)
		private String expectedEvidenceFingerprint;
		/** 退派原因，1至500字。 */
		@Schema(description = "退派原因，1至500字", requiredMode = Schema.RequiredMode.REQUIRED)
		private String reason;
	}

	@Data
	@Schema(description = "异岗审核退派申请")
	public static class ReviewCommand {
		/** 退派申请主键。 */
		@Schema(description = "退派申请主键", requiredMode = Schema.RequiredMode.REQUIRED)
		private Long requestId;
		/** 本次审批永久幂等请求号。 */
		@Schema(description = "本次审批永久幂等请求号", requiredMode = Schema.RequiredMode.REQUIRED)
		private String operationRequestId;
		/** 申请冻结的64位事实指纹。 */
		@Schema(description = "申请冻结的64位事实指纹", requiredMode = Schema.RequiredMode.REQUIRED)
		private String expectedEvidenceFingerprint;
		/** true批准，false驳回。 */
		@Schema(description = "true批准，false驳回", requiredMode = Schema.RequiredMode.REQUIRED)
		private Boolean approved;
		/** 审核依据，1至500字。 */
		@Schema(description = "审核依据，1至500字", requiredMode = Schema.RequiredMode.REQUIRED)
		private String reason;
	}

	@Data
	@Schema(description = "原申请人撤回待审核退派申请")
	public static class WithdrawCommand {
		/** 退派申请主键。 */
		@Schema(description = "退派申请主键", requiredMode = Schema.RequiredMode.REQUIRED)
		private Long requestId;
		/** 本次撤回永久幂等请求号。 */
		@Schema(description = "本次撤回永久幂等请求号", requiredMode = Schema.RequiredMode.REQUIRED)
		private String operationRequestId;
		/** 撤回原因，1至500字。 */
		@Schema(description = "撤回原因，1至500字", requiredMode = Schema.RequiredMode.REQUIRED)
		private String reason;
	}

	@Data
	@Accessors(chain = true)
	@Schema(description = "退派资格及当前事实证据")
	public static class Evidence {
		/** 实际取证合同；V1为原整根未执行范围，QUOTA_RETURN_V1仅处理精确配额目标。 */
		@Schema(description = "MES_DISPATCH_RETURN_V1或MES_DISPATCH_QUOTA_RETURN_V1")
		private String evidenceVersion;
		/** 当前真实配额摘要；旧无账、动态整组及其他不适用的新范围为空。 */
		@Schema(description = "目标配额摘要，不代替退派资格或实际释放证明")
		private AllocationEvidence allocation;
		/** 当前真实目标派工；新配额范围也不包含其他兄弟任务。 */
		@Schema(description = "当前真实目标派工")
		private MesTaskArrangeEntity arrange;
		/** 原生产工单当前状态，仅展示和实时准入，不进入新目标级稳定指纹。 */
		@Schema(description = "当前父工单状态")
		private String taskStatus;
		/** 真实工序当前状态；不得将它当作目标自身执行状态。 */
		@Schema(description = "当前父工序状态")
		private String produceTaskStatus;
		/** 只属于该目标的工装领用及使用证据。 */
		@Schema(description = "目标工装执行证据")
		private List<MesToolingExecutionEntity> toolingExecutions;
		/** 按实际evidenceVersion生成的SHA256，提交/审批必须带所见原值。 */
		@Schema(description = "当前事实证据SHA256")
		private String evidenceFingerprint;
		/** 当前证据无阻断时为true，不代替正式审批和锁内再次取证。 */
		@Schema(description = "当前是否具备申请资格")
		private Boolean eligible;
		/** 当前真实阻断项；读取失败不以空列表冒充允许执行。 */
		@Schema(description = "当前退派阻断项")
		private List<String> blockers;
		/** 该目标已有待审申请，无则为空；不能再建另一申请覆盖原件。 */
		@Schema(description = "现有待审退派申请")
		private MesDispatchReturnRequestEntity activeRequest;
	}

	@Data
	@Accessors(chain = true)
	@Schema(description = "退派申请详情、当前事实和不可变履历")
	public static class Detail {
		/** 当前同组织原申请，包含其不可变证据和已发生结果。 */
		@Schema(description = "当前退派申请")
		private MesDispatchReturnRequestEntity request;
		/** 按原申请证据版本重采的当前目标事实。 */
		@Schema(description = "原合同下的当前事实")
		private Evidence currentEvidence;
		/** 同申请不可变操作履历，原操作状态与当前申请状态分开解释。 */
		@Schema(description = "原操作履历")
		private List<MesDispatchReturnOperationEntity> operations;
	}

	/** 精确派工配额的只读摘要；不把占用量当作生产或仓库实绩。 */
	@Data
	@Accessors(chain = true)
	@Schema(description = "独立退派的原配额及当前占用")
	public static class AllocationEvidence {
		/** 原400出生配额Long身份，按文本传输。 */
		@Schema(description = "原配额身份")
		@JsonSerialize(using = ToStringSerializer.class)
		private Long id;
		/** 原命令内不可变行标识，不是工序编码。 */
		@Schema(description = "原稳定分配行标识")
		private String allocationKey;
		/** 本行出生数量，单位沿原工序物料基本单位。 */
		@Schema(description = "原配额数量，精确十进制文本")
		@JsonSerialize(using = ToStringSerializer.class)
		private BigDecimal allocatedQuantity;
		/** 当前永久占用；只有严格零执行关闭结算后为0。 */
		@Schema(description = "当前配额占用，精确十进制文本")
		@JsonSerialize(using = ToStringSerializer.class)
		private BigDecimal occupiedQuantity;
		/** RESERVED占原量、FINISHED永久占原量、UNEXECUTED_CLOSED已释放。 */
		@Schema(description = "原配额结算状态")
		private String settlementStatus;
	}

	/** 以完整原命令核对的永久操作回执；查无不证明未执行。 */
	@Data
	@Accessors(chain = true)
	@Schema(description = "受控退派原号只读核对结果")
	public static class Receipt {
		/** APPLIED为原操作已记账，ABSENT仅表示当前未查到，不允许据此换号。 */
		@Schema(description = "APPLIED或ABSENT")
		private String status;
		/** 原命令类别SUBMIT、REVIEW、WITHDRAW，与实际审批后状态分开。 */
		@Schema(description = "核对的原操作类别")
		private String kind;
		/** 原提交号或原operationRequestId，不是申请Long主键。 */
		@Schema(description = "本次核对的原操作请求号")
		private String requestId;
		/** APPLIED时的不可变原操作；原afterStatus可能已被后续合法申请状态推进。 */
		@Schema(description = "原永久操作，ABSENT为空")
		private MesDispatchReturnOperationEntity operation;
		/** APPLIED时同组织当前申请；不是将当前状态伪装为原操作时状态。 */
		@Schema(description = "当前实际退派申请，ABSENT为空")
		private MesDispatchReturnRequestEntity request;
	}
}

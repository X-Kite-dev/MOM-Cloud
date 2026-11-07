package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.kitex.mom.mdm.dto.MdmShiftOccurrence;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 受控替代派工合同；所有业务Long身份在HTTP边界用精确字符串。
 */
public final class MesDispatchCorrectionDTO {

	/**
	 * 外层V1回执合同；历史原号仍按该版本读取，不重写既有摘要。
	 */
	public static final String VERSION = "MES_DISPATCH_CORRECTION_V1";

	private MesDispatchCorrectionDTO() {
	}

	/**
	 * 单次实名命令；旧字段顺序和空值序列化保持历史摘要，新增日期仅非空时参与正文。
	 */
	@Data
	@JsonIgnoreProperties(ignoreUnknown = false)
	@JsonPropertyOrder({"requestId", "action", "arrangeId", "correctionId", "expectedFingerprint", "shiftCode",
			"worksiteCodes", "reason", "shiftDate"})
	public static class Command {

		/**
		 * 本次实名命令的永久原请求号，同号不得换动作、正文或操作者。
		 */
		private String requestId;

		/**
		 * 办理动作：SUBMIT申请、APPROVE批准、REJECT驳回、WITHDRAW撤回。
		 */
		private String action;

		/**
		 * 原派工精确身份；仅提交新申请时携带。
		 */
		private String arrangeId;

		/**
		 * 原修正申请精确身份；审批、驳回和撤回时携带。
		 */
		private String correctionId;

		/**
		 * 预览或原申请的完整证据SHA256；撤回不携带。
		 */
		private String expectedFingerprint;

		/**
		 * 目标正式班次实例编码；新申请与明确所属日一起提交。
		 */
		private String shiftCode;

		/**
		 * 目标授权工位编码集合，仅申请时指定。
		 */
		private List<String> worksiteCodes;

		/**
		 * 本次实名申请、审核或撤回的办理依据。
		 */
		private String reason;

		/**
		 * 新申请明确选择的班次所属日；历史原文可空，空值不参与旧命令摘要。
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		private LocalDate shiftDate;

		/**
		 * 拒绝合同外字段，防止办理原申请时夹带未授权事实。
		 */
		@JsonAnySetter
		public void rejectUnknown(String field, Object value) {
			throw new IllegalArgumentException("修正命令包含未授权字段：" + field);
		}
	}

	/**
	 * 正式日期班次或原工序授权工位候选，不接受手工补造的来源。
	 */
	@Data
	public static class Choice {

		/**
		 * 候选的精确编码；班次为日期实例码，工位为授权主数据码。
		 */
		private String code;

		/**
		 * 候选班次或工位的正式名称。
		 */
		private String name;

		/**
		 * 日期班次候选的明确所属日；工位候选及历史回执可空。
		 */
		private LocalDate targetShiftDate;

		/**
		 * 正式班次规则生成的完整实例；工位候选及历史回执可空。
		 */
		private MdmShiftOccurrence targetShiftOccurrence;

		public Choice() {
		}

		public Choice(String code, String name) {
			this.code = code;
			this.name = name;
		}
	}

	/**
	 * 当前实际组织、原执行事实及明确日期目标的只读资格观察。
	 */
	@Data
	public static class Preview {

		/**
		 * 本响应的外层合同版本，保持旧V1回执兼容。
		 */
		private String contractVersion = VERSION;

		/**
		 * 当前实名操作范围的租户精确字符串身份。
		 */
		private String tenantId;

		/**
		 * 当前实际工厂编码，来源已通过组织授权校验。
		 */
		private String factoryCode;

		/**
		 * 本次读取当前事实的服务端观察时刻。
		 */
		private LocalDateTime observedAt;

		/**
		 * 原派工、目标日期实例及资源模板等完整观察证据SHA256。
		 */
		private String evidenceFingerprint;

		/**
		 * 当前事实和具体目标是否满足全部修正申请门禁。
		 */
		private boolean eligible;

		/**
		 * 不能申请或批准的明确业务阻断，不将未知事实当作可行。
		 */
		private List<String> blockers;

		/**
		 * 当前实际组织的原派工及其冻结执行依据。
		 */
		private MesTaskArrangeEntity arrange;

		/**
		 * 原申请冻结的目标班次实例编码，不从编码推测日期。
		 */
		private String targetShiftCode;

		/**
		 * 原申请冻结的目标班次名称。
		 */
		private String targetShiftName;

		/**
		 * 目标班次的明确所属日；无完整日期依据的历史申请可空。
		 */
		private LocalDate targetShiftDate;

		/**
		 * 目标正式班次的规则身份、所属日期和跨夜窗口；历史申请可空。
		 */
		private MdmShiftOccurrence targetShiftOccurrence;

		/**
		 * 原申请冻结的目标授权工位编码集合。
		 */
		private List<String> targetWorksiteCodes;

		/**
		 * 明确所选日期上由正式启用规则生成的班次实例候选。
		 */
		private List<Choice> candidateShifts;

		/**
		 * 原真实工序任务允许选择的工位候选。
		 */
		private List<Choice> candidateWorksites;
	}

	/**
	 * 原申请冻结目标、独立审核及替代派工结果；历史缺日期时不补造。
	 */
	@Data
	public static class Correction {

		/**
		 * 目标日期依据无法读取的具体原因，区分历史缺少合同与原快照损坏；非空时不得批准。
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		private String targetShiftIssue;

		/**
		 * 修正申请精确字符串身份。
		 */
		private String id;

		/**
		 * 原派工精确身份；仅提交新申请时携带。
		 */
		private String arrangeId;

		/**
		 * 原派工业务编码，历史事实保持原记录。
		 */
		private String arrangeCode;

		/**
		 * 原生产工单编码，范围限定于当前租户及工厂。
		 */
		private String taskCode;

		/**
		 * 原工序任务编码，替代派工仍归属该精确工序。
		 */
		private String produceTaskCode;

		/**
		 * 申请状态：SUBMITTED待审、APPROVED已替代、REJECTED已驳回、WITHDRAWN已撤回。
		 */
		private String status;

		/**
		 * 原退派资格证据SHA256，批准时必须重新匹配。
		 */
		private String sourceFingerprint;

		/**
		 * 原派工、目标日期实例及资源模板等完整观察证据SHA256。
		 */
		private String evidenceFingerprint;

		/**
		 * 原申请冻结的目标班次实例编码，不从编码推测日期。
		 */
		private String targetShiftCode;

		/**
		 * 原申请冻结的目标班次名称。
		 */
		private String targetShiftName;

		/**
		 * 目标班次的明确所属日；无完整日期依据的历史申请可空。
		 */
		private LocalDate targetShiftDate;

		/**
		 * 目标正式班次的规则身份、所属日期和跨夜窗口；历史申请可空。
		 */
		private MdmShiftOccurrence targetShiftOccurrence;

		/**
		 * 原申请冻结的目标授权工位编码集合。
		 */
		private List<String> targetWorksiteCodes;

		/**
		 * 本次实名申请、审核或撤回的办理依据。
		 */
		private String reason;

		/**
		 * 原实名申请人的精确字符串身份。
		 */
		private String applicantId;

		/**
		 * 原实名申请人账号快照。
		 */
		private String applicantCode;

		/**
		 * 原实名申请人姓名快照。
		 */
		private String applicantName;

		/**
		 * 本修正申请成功冻结并提交的业务时刻。
		 */
		private LocalDateTime submittedTime;

		/**
		 * 独立审核人的精确字符串身份，未审核时可空。
		 */
		private String reviewerId;

		/**
		 * 独立审核人账号快照，未审核时可空。
		 */
		private String reviewerCode;

		/**
		 * 独立审核人姓名快照，未审核时可空。
		 */
		private String reviewerName;

		/**
		 * 独立批准或驳回的业务时刻，未审核时可空。
		 */
		private LocalDateTime reviewedTime;

		/**
		 * 独立审核人保留的精确办理依据。
		 */
		private String reviewReason;

		/**
		 * 正式批准后生成的替代派工精确身份，未批准时可空。
		 */
		private String replacementArrangeId;

		/**
		 * 正式批准后生成的替代派工编码，未批准时可空。
		 */
		private String replacementArrangeCode;

		/**
		 * 原派工修正前的永久事实快照。
		 */
		private String beforeSnapshot;

		/**
		 * 正式批准后替代派工的永久事实快照，未批准时可空。
		 */
		private String afterSnapshot;
	}

	/**
	 * 原申请的一次永久实名操作履历。
	 */
	@Data
	public static class Operation {

		/**
		 * 本次实名命令的永久原请求号，同号不得换动作、正文或操作者。
		 */
		private String requestId;

		/**
		 * 办理动作：SUBMIT申请、APPROVE批准、REJECT驳回、WITHDRAW撤回。
		 */
		private String action;

		/**
		 * 本次历史操作的实名操作者精确身份。
		 */
		private String actorId;

		/**
		 * 本次历史操作的实名账号快照。
		 */
		private String actorCode;

		/**
		 * 本次实名申请、审核或撤回的办理依据。
		 */
		private String reason;

		/**
		 * 本次历史操作永久回执的生成时刻。
		 */
		private LocalDateTime createdTime;
	}

	/**
	 * 原申请及其永久履历，待审时另含按冻结日期重采的资格。
	 */
	@Data
	public static class Detail {

		/**
		 * 本响应的外层合同版本，保持旧V1回执兼容。
		 */
		private String contractVersion = VERSION;

		/**
		 * 当前实名操作范围的租户精确字符串身份。
		 */
		private String tenantId;

		/**
		 * 当前实际工厂编码，来源已通过组织授权校验。
		 */
		private String factoryCode;

		/**
		 * 本次读取当前事实的服务端观察时刻。
		 */
		private LocalDateTime observedAt;

		/**
		 * 原修正申请及其冻结目标和前后事实。
		 */
		private Correction correction;

		/**
		 * 该申请按业务顺序保留的永久操作履历。
		 */
		private List<Operation> operations;

		/**
		 * 待审申请按原冻结日期重新观察的资格；不以今天替代历史目标。
		 */
		private Preview currentPreview;
	}

	/**
	 * 永久原请求号的固定回执，不以当前状态代替历史结果。
	 */
	@Data
	public static class Resolution {

		/**
		 * 本响应的外层合同版本，保持旧V1回执兼容。
		 */
		private String contractVersion = VERSION;

		/**
		 * 当前实名操作范围的租户精确字符串身份。
		 */
		private String tenantId;

		/**
		 * 当前实际工厂编码，来源已通过组织授权校验。
		 */
		private String factoryCode;

		/**
		 * 本次实名命令的永久原请求号，同号不得换动作、正文或操作者。
		 */
		private String requestId;

		/**
		 * 原请求回执状态：APPLIED已办理、ABSENT未找到、CANCELLED已封存。
		 */
		private String state;

		/**
		 * 原请求成功时冻结的办理结果，未受理或取消时可空。
		 */
		private Detail result;
	}

	/**
	 * 当前实际组织范围内的修正申请分页结果。
	 */
	@Data
	public static class Page {

		/**
		 * 本响应的外层合同版本，保持旧V1回执兼容。
		 */
		private String contractVersion = VERSION;

		/**
		 * 当前实名操作范围的租户精确字符串身份。
		 */
		private String tenantId;

		/**
		 * 当前实际工厂编码，来源已通过组织授权校验。
		 */
		private String factoryCode;

		/**
		 * 本次读取当前事实的服务端观察时刻。
		 */
		private LocalDateTime observedAt;

		/**
		 * 当前组织和筛选范围的修正申请分页记录。
		 */
		private List<Correction> records;

		/**
		 * 当前组织和筛选范围的申请总条数。
		 */
		private long total;

		/**
		 * 从1开始的当前页码。
		 */
		private long current;

		/**
		 * 本页最大记录条数。
		 */
		private long size;
	}
}

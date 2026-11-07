package com.kitex.mom.qms.agent.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 已保存质量假设引用原CAPA的窄合同；所有主键按完整十进制文本传输。
 * 原建议验证计划不属于人工提交的真实验证证据。
 */
public final class QmsRootCauseImportDTO {

	private QmsRootCauseImportDTO() {
	}

	/** 固定AI保存来源定位，不接受客户端提供证明正文、URL或组织身份。 */
	public record Source(
			/** 原质量运行主键。 */
			@NotBlank
			@Pattern(regexp = "[1-9][0-9]{0,18}")
			@JsonDeserialize(using = QmsRootCauseTextDeserializer.class)
			String runId,
			/** 原结构化分析产物主键。 */
			@NotBlank
			@Pattern(regexp = "[1-9][0-9]{0,18}")
			@JsonDeserialize(using = QmsRootCauseTextDeserializer.class)
			String analysisArtifactId,
			/** 原分析正文SHA-256。 */
			@NotBlank
			@Pattern(regexp = "[a-f0-9]{64}")
			@JsonDeserialize(using = QmsRootCauseTextDeserializer.class)
			String analysisContentHash,
			/** 原结构化分析内的唯一假设编号。 */
			@NotBlank
			@Pattern(regexp = "H[1-9][0-9]{0,2}")
			@JsonDeserialize(using = QmsRootCauseTextDeserializer.class)
			String hypothesisId) {
	}

	/** 人工完整命令；首次请求及取消始终携带同一份内容。 */
	public record Command(
			/** 当前组织内永久原号，同号异人异载荷拒绝。 */
			@NotBlank
			@Pattern(regexp = "[A-Za-z0-9._:-]{8,100}")
			@JsonDeserialize(using = QmsRootCauseTextDeserializer.class)
			String requestId,
			/** 人工选定的原CAPA主键。 */
			@NotBlank
			@Pattern(regexp = "[1-9][0-9]{0,18}")
			@JsonDeserialize(using = QmsRootCauseTextDeserializer.class)
			String capaId,
			/** 人工阅读并确认的目标当前周期。 */
			@NotNull
			@Positive
			@JsonDeserialize(using = QmsRootCauseCycleDeserializer.class)
			Integer expectedCycleNo,
			/** 选择目标时原QMS全量案件事实摘要。 */
			@NotBlank
			@Pattern(regexp = "[a-f0-9]{64}")
			@JsonDeserialize(using = QmsRootCauseTextDeserializer.class)
			String expectedCaseFingerprint,
			/** 原质量运行主键。 */
			@NotBlank
			@Pattern(regexp = "[1-9][0-9]{0,18}")
			@JsonDeserialize(using = QmsRootCauseTextDeserializer.class)
			String runId,
			/** 原结构化分析产物主键。 */
			@NotBlank
			@Pattern(regexp = "[1-9][0-9]{0,18}")
			@JsonDeserialize(using = QmsRootCauseTextDeserializer.class)
			String analysisArtifactId,
			/** 原分析正文SHA-256。 */
			@NotBlank
			@Pattern(regexp = "[a-f0-9]{64}")
			@JsonDeserialize(using = QmsRootCauseTextDeserializer.class)
			String analysisContentHash,
			/** 原分析内的假设编号。 */
			@NotBlank
			@Pattern(regexp = "H[1-9][0-9]{0,2}")
			@JsonDeserialize(using = QmsRootCauseTextDeserializer.class)
			String hypothesisId,
			/** 人工采用的原QMS分析方法枚举。 */
			@NotBlank
			@Pattern(regexp = "5WHY|FISHBONE|FAULT_TREE|IS_IS_NOT|OTHER")
			@JsonDeserialize(using = QmsRootCauseTextDeserializer.class)
			String analysisMethod,
			/** 人工审阅修改后的正式假设，不覆盖AI原假设。 */
			@NotBlank
			@Size(max = 2000)
			@JsonDeserialize(using = QmsRootCauseTextDeserializer.class)
			String causeDescription,
			/** 人工实际试验、数据或审核证据；不能用AI建议计划冒充。 */
			@NotBlank
			@Size(max = 1000)
			@JsonDeserialize(using = QmsRootCauseTextDeserializer.class)
			String evidence) {

		/** 取得本命令不可替换的原分析定位。 */
		public Source source() {
			return new Source(runId, analysisArtifactId, analysisContentHash, hypothesisId);
		}
	}

	/** 原号回执；只有ACCEPTED具有真实根因身份，不把PREPARED表述为业务成功。 */
	public record Receipt(
			/** 原请求号。 */
			String requestId,
			/** PREPARED待核、ACCEPTED已受理、CANCELLED永久取消未受理。 */
			String status,
			/** 原CAPA主键。 */
			String capaId,
			/** 登记时核实的原CAPA编号，恢复后仍可精确定位。 */
			String capaNo,
			/** 接受目标周期或原命令期望周期。 */
			Integer cycleNo,
			/** 原命令冻结的案件事实摘要，恢复时不替换为今日指纹。 */
			String expectedCaseFingerprint,
			/** 原运行主键。 */
			String runId,
			/** 原分析产物主键。 */
			String analysisArtifactId,
			/** 原分析正文摘要。 */
			String analysisContentHash,
			/** 原分析假设编号。 */
			String hypothesisId,
			/** 实际提出人员主键，非模型或服务账号。 */
			String actorUserId,
			/** 人工分析方法。 */
			String analysisMethod,
			/** 人工正式根因描述。 */
			String causeDescription,
			/** 人工真实证据。 */
			String evidence,
			/** ACCEPTED时真实根因主键，其他状态为null。 */
			String causeId,
			/** ACCEPTED时真实根因编号。 */
			String causeNo,
			/** 真实根因当前验证状态，不由AI确认。 */
			String verificationStatus) {
	}

	/** 同工单既有案件候选，数量由数据库真实分页计算。 */
	public record Candidate(
			/** 原CAPA主键。 */
			String capaId,
			/** 原CAPA编号。 */
			String capaNo,
			/** 当前周期。 */
			Integer cycleNo,
			/** 原业务阶段。 */
			String status,
			/** 原问题标题。 */
			String title,
			/** 当前全量案件事实摘要。 */
			String sourceFingerprint,
			/** 仅当前ROOT_CAUSE周期且源权限完整时允许提出。 */
			boolean eligible,
			/** 不适用原因，无阻断时为空。 */
			String blockReason) {
	}
}

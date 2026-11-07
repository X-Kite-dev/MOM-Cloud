package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 标准包装命令与读模型；原永久回执与后来当前包装状态分列，不重算原成功结果。
 */
public final class MesPackagingDTO {

	private MesPackagingDTO() {
	}

	/** 历史派工显式采用命令，不改原派工和历史报工。 */
	@Data
	@Accessors(chain = true)
	@Schema(description = "历史派工显式采用命令，不改原派工和历史报工")
	public static class Adopt {

		/** 当前工厂内精确派工编码。 */
		@Schema(description = "当前工厂内精确派工编码")
		@NotBlank
		@Size(max = 100)
		private String arrangeCode;

		/** 显式选择的同组织已启用包装规则ID。 */
		@Schema(description = "显式选择的同组织已启用包装规则ID")
		@NotNull
		private Long ruleId;

		/** 显式采用业务原因。 */
		@Schema(description = "显式采用业务原因")
		@NotBlank
		@Size(max = 500)
		private String reason;

	}

	/** 同派工尾箱封箱原命令；成员和工位一并永久冻结。 */
	@Data
	@Accessors(chain = true)
	@Schema(description = "同派工尾箱封箱原命令；成员和工位一并永久冻结")
	public static class Seal {

		/** 实名租户内永久原请求号。 */
		@Schema(description = "实名租户内永久原请求号")
		@NotBlank
		@Size(max = 100)
		private String requestId;

		/** 精确派工编码。 */
		@Schema(description = "精确派工编码")
		@NotBlank
		@Size(max = 100)
		private String arrangeCode;

		/** 显式选择的真实待装内容ID，服务端固定升序。 */
		@Schema(description = "显式选择的真实待装内容ID，服务端固定升序")
		@NotEmpty
		@Size(max = 10000)
		private List<Long> recordIds;

		/** 尾箱封箱业务原因。 */
		@Schema(description = "尾箱封箱业务原因")
		@NotBlank
		@Size(max = 500)
		private String reason;

		/** 当前选中现场工位，必须等于派工实际接收工位。 */
		@Schema(description = "当前选中现场工位，必须等于派工实际接收工位")
		@NotBlank
		@Size(max = 100)
		private String worksiteCode;

	}

	/** 永久封存尚未受理的原封箱号；已有成功只回原结果。 */
	@Data
	@Accessors(chain = true)
	@Schema(description = "永久封存尚未受理的原封箱号；已有成功只回原结果")
	public static class Close {

		/** 原号原正文，不能用当前扫描内容替换。 */
		@Schema(description = "原号原正文，不能用当前扫描内容替换")
		@Valid
		@NotNull
		private Seal command;

		/** 封存未受理请求的业务原因。 */
		@Schema(description = "封存未受理请求的业务原因")
		@NotBlank
		@Size(max = 500)
		private String reason;

	}

	/** 已持久采用的包装依据摘要。 */
	@Data
	@Accessors(chain = true)
	@Schema(description = "已持久采用的包装依据摘要")
	public static class Adoption {

		/** 包装采用主键十进制原文。 */
		@Schema(description = "包装采用主键十进制原文")
		private String id;

		/** 实际派工编码。 */
		@Schema(description = "实际派工编码")
		private String arrangeCode;

		/** 冻结包装模式。 */
		@Schema(description = "冻结包装模式")
		private String reportMode;

		/** NEW_DISPATCH或LEGACY_ADOPTION。 */
		@Schema(description = "NEW_DISPATCH或LEGACY_ADOPTION")
		private String adoptionKind;

		/** 原包装规则主键十进制原文。 */
		@Schema(description = "原包装规则主键十进制原文")
		private String ruleId;

		/** 原包装规则编码。 */
		@Schema(description = "原包装规则编码")
		private String ruleCode;

		/** 冻结每箱容量，单位为报工基本单位。 */
		@Schema(description = "冻结每箱容量，单位为报工基本单位")
		private Integer capacity;

		/** 采用完整原文SHA-256。 */
		@Schema(description = "采用完整原文SHA-256")
		private String sourceFingerprint;

		/** 实际采用人主键十进制原文。 */
		@Schema(description = "实际采用人主键十进制原文")
		private String actorId;

		/** 实际采用账号。 */
		@Schema(description = "实际采用账号")
		private String actorCode;

		/** 原采用原因。 */
		@Schema(description = "原采用原因")
		private String reason;

		/** 服务器实际采用时刻。 */
		@Schema(description = "服务器实际采用时刻")
		private LocalDateTime adoptedAt;

	}

	/** 当前真实待装成员；不等于历史包装记录总数。 */
	@Data
	@Accessors(chain = true)
	@Schema(description = "当前真实待装成员；不等于历史包装记录总数")
	public static class Pending {

		/** 原待装内容主键十进制原文。 */
		@Schema(description = "原待装内容主键十进制原文")
		private String recordId;

		/** 真实GOOD报工主键十进制原文。 */
		@Schema(description = "真实GOOD报工主键十进制原文")
		private String reportId;

		/** 原规范单件标识。 */
		@Schema(description = "原规范单件标识")
		private String transferCode;

		/** 当前该待装成员数量，单位为原报工基本单位。 */
		@Schema(description = "当前该待装成员数量，单位为原报工基本单位")
		private BigDecimal quantity;

		/** 原待装内容生成时刻。 */
		@Schema(description = "原待装内容生成时刻")
		private LocalDateTime createdAt;

		/** 当前只读来源采样是否可参与封箱，不替代提交时锁内复核。 */
		@Schema(description = "当前只读来源采样是否可参与封箱")
		private Boolean sealAllowed;

		/** 当前待质量放行、来源占用等受限原因，可封时为空。 */
		@Schema(description = "当前受限原因，可封时为空")
		private String holdReason;

	}

	/** 原封箱命令及结果，当前包装状态独立列示。 */
	@Data
	@Accessors(chain = true)
	@Schema(description = "原封箱命令及结果，当前包装状态独立列示")
	public static class Receipt {

		/** 永久原请求号。 */
		@Schema(description = "永久原请求号")
		private String requestId;

		/** ACCEPTED封箱成功或CANCELLED永久未执行。 */
		@Schema(description = "ACCEPTED封箱成功或CANCELLED永久未执行")
		private String acceptedStatus;

		/** 永久原命令JSON，用于核对原正文。 */
		@Schema(description = "永久原命令JSON，用于核对原正文")
		private String commandSnapshot;

		/** 永久原结果JSON，不随拆箱或重组改写。 */
		@Schema(description = "永久原结果JSON，不随拆箱或重组改写")
		private String resultSnapshot;

		/** 原实名租户主键十进制原文。 */
		@Schema(description = "原实名租户主键十进制原文")
		private String tenantId;

		/** 原实际工厂编码。 */
		@Schema(description = "原实际工厂编码")
		private String factoryCode;

		/** 原请求人主键十进制原文。 */
		@Schema(description = "原请求人主键十进制原文")
		private String actorId;

		/** 原请求账号。 */
		@Schema(description = "原请求账号")
		private String actorCode;

		/** 原精确派工编码。 */
		@Schema(description = "原精确派工编码")
		private String arrangeCode;

		/** 当时实际生成箱码，CANCELLED为空。 */
		@Schema(description = "当时实际生成箱码，CANCELLED为空")
		private String wrapCode;

		/** 当时实际封箱数量，CANCELLED为零。 */
		@Schema(description = "当时实际封箱数量，CANCELLED为零")
		private BigDecimal quantity;

		/** 原冻结每箱容量。 */
		@Schema(description = "原冻结每箱容量")
		private Integer capacity;

		/** AUTO_FULL/TAIL/QUANTITY。 */
		@Schema(description = "AUTO_FULL/TAIL/QUANTITY")
		private String sealMode;

		/** 永久处理时刻。 */
		@Schema(description = "永久处理时刻")
		private LocalDateTime acceptedAt;

		/** 后来当前包装状态，箱档不可用或取消时为空。 */
		@Schema(description = "后来当前包装状态，箱档不可用或取消时为空")
		private String currentWrapStatus;

		/** 后来当前箱内数量，箱档不可用或取消时为空。 */
		@Schema(description = "后来当前箱内数量，箱档不可用或取消时为空")
		private BigDecimal currentQuantity;

	}

	/** 精确派工当前包装工作区。 */
	@Data
	@Accessors(chain = true)
	@Schema(description = "精确派工当前包装工作区")
	public static class Progress {

		/** 实际派工编码。 */
		@Schema(description = "实际派工编码")
		private String arrangeCode;

		/** 实际包装模式。 */
		@Schema(description = "实际包装模式")
		private String reportMode;

		/** 实际已采用包装依据，旧任务尚未采用时为空。 */
		@Schema(description = "实际已采用包装依据，旧任务尚未采用时为空")
		private Adoption adoption;

		/** 真实STAGED内容数量合计，不含已封箱和已冲销。 */
		@Schema(description = "真实STAGED内容数量合计，不含已封箱和已冲销")
		private BigDecimal stagedQuantity;

		/** 真实STAGED成员，按生成时刻和主键升序。 */
		@Schema(description = "真实STAGED成员，按生成时刻和主键升序")
		private List<Pending> stagedRecords;

		/** 本派工最近一笔真实成功封箱及当前箱档状态，无箱时为空。 */
		@Schema(description = "本派工最近一笔真实成功封箱及当前箱档状态，无箱时为空")
		private Receipt latestSeal;

	}

}

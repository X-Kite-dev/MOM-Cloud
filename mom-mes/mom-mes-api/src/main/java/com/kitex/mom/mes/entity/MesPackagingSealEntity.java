package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 封箱永久成功命令与原回执；后续拆箱不重写本账。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TenantTable
@TableName("mes_packaging_seal")
@Schema(description = "封箱永久成功命令与原回执；后续拆箱不重写本账。")
public class MesPackagingSealEntity extends BaseEntity<MesPackagingSealEntity> {

	/** 实际工厂编码。 */
	@Schema(description = "实际工厂编码")
	private String factoryCode;

	/** ACCEPTED封箱成功或CANCELLED永久未执行。 */
	@Schema(description = "ACCEPTED封箱成功或CANCELLED永久未执行")
	private String acceptedStatus;

	/** 未受理封存原因，成功时为空。 */
	@Schema(description = "未受理封存原因，成功时为空")
	private String closeReason;

	/** 本派工永久包装采用主键。 */
	@Schema(description = "本派工永久包装采用主键")
	private Long adoptionId;

	/** 原封箱请求号，租户内永久唯一。 */
	@Schema(description = "原封箱请求号，租户内永久唯一")
	private String requestId;

	/** 封箱命令协议 MES_PACKAGING_SEAL_V1。 */
	@Schema(description = "封箱命令协议 MES_PACKAGING_SEAL_V1")
	private String commandVersion;

	/** 包含实名组织模式原因及有序成员的原命令JSON。 */
	@Schema(description = "包含实名组织模式原因及有序成员的原命令JSON")
	private String commandSnapshot;

	/** 封箱原命令UTF-8 SHA-256。 */
	@Schema(description = "封箱原命令UTF-8 SHA-256")
	private String requestFingerprint;

	/** 实际请求人员主键。 */
	@Schema(description = "实际请求人员主键")
	private Long actorId;

	/** 实际请求账号。 */
	@Schema(description = "实际请求账号")
	private String actorCode;

	/** 真实原工单编码。 */
	@Schema(description = "真实原工单编码")
	private String taskCode;

	/** 真实原工序任务编码。 */
	@Schema(description = "真实原工序任务编码")
	private String produceTaskCode;

	/** 精确原派工主键。 */
	@Schema(description = "精确原派工主键")
	private Long arrangeId;

	/** 精确原派工编码。 */
	@Schema(description = "精确原派工编码")
	private String arrangeCode;

	/** AUTO_FULL自动满箱/TAIL手工尾箱/QUANTITY数量包装。 */
	@Schema(description = "AUTO_FULL自动满箱/TAIL手工尾箱/QUANTITY数量包装")
	private String sealMode;

	/** 固定自动原因或尾箱必填业务原因。 */
	@Schema(description = "固定自动原因或尾箱必填业务原因")
	private String reason;

	/** 本次装入数量，单位为原报工基本单位。 */
	@Schema(description = "本次装入数量，单位为原报工基本单位")
	private BigDecimal quantity;

	/** 本次采用的冻结每箱容量。 */
	@Schema(description = "本次采用的冻结每箱容量")
	private Integer capacity;

	/** 实际包装主档主键。 */
	@Schema(description = "实际包装主档主键")
	private Long wrapId;

	/** 实际唯一箱码，数量模式沿用已验证容器码。 */
	@Schema(description = "实际唯一箱码，数量模式沿用已验证容器码")
	private String wrapCode;

	/** 触发自动或数量封箱的GOOD报告ID，尾箱为空。 */
	@Schema(description = "触发自动或数量封箱的GOOD报告ID，尾箱为空")
	private Long sourceReportId;

	/** 同事务完成封箱的服务器时刻。 */
	@Schema(description = "同事务完成封箱的服务器时刻")
	private LocalDateTime acceptedAt;

	/** 不可变原封箱回执JSON，不代表后来当前包装状态。 */
	@Schema(description = "不可变原封箱回执JSON，不代表后来当前包装状态")
	private String resultSnapshot;

}

package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * PLAN原准备的不可变终结索引；只存真实MES终态，原准备及来源范围永久保留。
 */
@Data
@TenantTable
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName(value = "pln_engineering_resolution", schema = "mom_plan")
public class PlnEngineeringResolutionEntity extends BaseEntity<PlnEngineeringResolutionEntity> {

	/**
	 * 原准备所属的真实授权工厂编码。
	 */
	@Schema(description = "原准备所属的真实授权工厂编码。")
	private String factoryCode;

	/**
	 * 已独立提交的原PLAN准备父账主键。
	 */
	@Schema(description = "已独立提交的原PLAN准备父账主键。")
	private Long preparationId;

	/**
	 * 同租户及工厂内永久唯一的原PEP请求号。
	 */
	@Schema(description = "同租户及工厂内永久唯一的原PEP请求号。")
	private String requestId;

	/**
	 * 原准备稳定授权规范正文的UTF8 SHA256。
	 */
	@Schema(description = "原准备稳定授权规范正文的UTF8 SHA256。")
	private String authorityFingerprint;

	/**
	 * MES实际永久终态：APPLIED工时批准已生效，CANCELLED永久取消。
	 */
	@Schema(description = "MES实际永久终态：APPLIED工时批准已生效，CANCELLED永久取消。")
	private String decision;

	/**
	 * MES真实永久决策主键，不接受客户端自报成功。
	 */
	@Schema(description = "MES真实永久决策主键，不接受客户端自报成功。")
	private Long mesDecisionId;

	/**
	 * MES规范回执原文的UTF8 SHA256。
	 */
	@Schema(description = "MES规范回执原文的UTF8 SHA256。")
	private String mesDecisionFingerprint;

	/**
	 * 服务端取得的完整MES规范终态回执，最多64KiB。
	 */
	@Schema(description = "服务端取得的完整MES规范终态回执，最多64KiB。")
	private String mesReceiptSnapshot;

	/**
	 * MES首次永久决策的真实人员主键。
	 */
	@Schema(description = "MES首次永久决策的真实人员主键。")
	private Long actorId;

	/**
	 * MES首次永久决策的当地业务时间。
	 */
	@Schema(description = "MES首次永久决策的当地业务时间。")
	private LocalDateTime decidedAt;

	/**
	 * MES原决定依据，最多1000字符，不覆盖准备原因。
	 */
	@Schema(description = "MES原决定依据，最多1000字符，不覆盖准备原因。")
	private String reason;
}

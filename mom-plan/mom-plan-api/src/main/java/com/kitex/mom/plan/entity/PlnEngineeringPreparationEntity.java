package com.kitex.mom.plan.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * PLAN工程变更准备的永久原号凭据。仅记录准备及取消，不代表MES采用或工程生效。
 * 原始观察与来源范围不可修改；取消保留原证据并永久结束原请求号。
 * 数据库内部created_xid及scope_keys由守卫维护，专用Mapper不接收、写入或回传这些技术字段。
 */
@Data
@TenantTable
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName(value = "pln_engineering_preparation", schema = "mom_plan")
public class PlnEngineeringPreparationEntity extends BaseEntity<PlnEngineeringPreparationEntity> {

	/**
	 * 原请求所属的真实授权工厂；与租户共同限定全部查询及写入。
	 */
	private String factoryCode;

	/**
	 * 租户、工厂内永久唯一的准备请求号，取消后不可重用。
	 */
	private String requestId;

	/**
	 * 原规范请求UTF-8正文的SHA256，用于同号同事实核对。
	 */
	private String requestFingerprint;

	/**
	 * 不超过16KiB的原规范请求正文，不作为可回写请求对象。
	 */
	private String canonicalRequest;

	/**
	 * 原MES工单号；未受理取消墓碑仍保留用户原命令身份。
	 */
	private String taskCode;

	/**
	 * 观察到的原MES工单主键；从未受理的取消墓碑为空。
	 */
	private Long taskId;

	/**
	 * 原MES冻结工程发布主键；从未受理的取消墓碑为空。
	 */
	private Long sourceReleaseId;

	/**
	 * 原MES冻结工程包SHA256；从未受理的取消墓碑为空。
	 */
	private String sourceContentHash;

	/**
	 * 用户申请的MDM正式目标工程发布主键，不代表已采用。
	 */
	private Long targetReleaseId;

	/**
	 * 原申请正式目标工程包SHA256，不按今日主数据重算。
	 */
	private String targetContentHash;

	/**
	 * 原命令要求的PLAN实际分配观察摘要；取消墓碑也保留。
	 */
	private String observationFingerprint;

	/**
	 * 准备时完整分配观察的序列化原文，最大20MiB；未受理取消为空。
	 */
	private String observationSnapshot;

	/**
	 * 完整观察原文按UTF-8计算的SHA256；未受理取消为空。
	 */
	private String observationSnapshotHash;

	/**
	 * 原准备原因，最多1000个字符，取消不得覆盖。
	 */
	private String reason;

	/**
	 * PREPARED已准备并保护来源；CANCELLED原号永久结束。
	 */
	private String status;

	/**
	 * 原实名申请人主键；与公共创建账号快照区分。
	 */
	private Long applicantId;

	/**
	 * 首次完成PLAN准备的当地业务时间；未受理取消为空。
	 */
	private LocalDateTime preparedAt;

	/**
	 * 原号取消的实名操作人主键；PREPARED为空。
	 */
	private Long cancelledBy;

	/**
	 * 原号永久取消的当地业务时间；PREPARED为空。
	 */
	private LocalDateTime cancelledAt;

	/**
	 * 取消原因，最多1000个字符；PREPARED为空。
	 */
	private String cancelReason;
}

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
 * 普通PLAN工单节点标准工时的永久互斥决策；采用及取消同原号只能有一个终态。
 */
@Data
@TenantTable
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName(value = "mes_plan_engineering_decision", schema = "mom_mes")
public class MesPlanEngineeringDecisionEntity extends BaseEntity<MesPlanEngineeringDecisionEntity> {

	/**
	 * 原准备与实际MES工单共同所属的真实工厂。
	 */
	@Schema(description = "原准备与实际MES工单共同所属的真实工厂。")
	private String factoryCode;

	/**
	 * 原已提交PLAN准备父账主键。
	 */
	@Schema(description = "原已提交PLAN准备父账主键。")
	private Long preparationId;

	/**
	 * 与PLAN原准备共用的永久PEP请求号，同组织唯一。
	 */
	@Schema(description = "与PLAN原准备共用的永久PEP请求号，同组织唯一。")
	private String requestId;

	/**
	 * PLAN稳定授权规范正文的UTF8 SHA256。
	 */
	@Schema(description = "PLAN稳定授权规范正文的UTF8 SHA256。")
	private String authorityFingerprint;

	/**
	 * PLAN稳定授权的完整规范正文，最多64KiB。
	 */
	@Schema(description = "PLAN稳定授权的完整规范正文，最多64KiB。")
	private String authoritySnapshot;

	/**
	 * 原MES工单真实主键，禁止同编码新对象替代。
	 */
	@Schema(description = "原MES工单真实主键，禁止同编码新对象替代。")
	private Long taskId;

	/**
	 * 原普通PLAN生产工单编码。
	 */
	@Schema(description = "原普通PLAN生产工单编码。")
	private String taskCode;

	/**
	 * 原工单产品编码，与准备及工程产品一致。
	 */
	@Schema(description = "原工单产品编码，与准备及工程产品一致。")
	private String productCode;

	/**
	 * 原工单冻结工程发布主键。
	 */
	@Schema(description = "原工单冻结工程发布主键。")
	private Long sourceReleaseId;

	/**
	 * 原工单冻结工程完整正文SHA256。
	 */
	@Schema(description = "原工单冻结工程完整正文SHA256。")
	private String sourceContentHash;

	/**
	 * 本次正式目标工程发布主键，未改原工单工程。
	 */
	@Schema(description = "本次正式目标工程发布主键，未改原工单工程。")
	private Long targetReleaseId;

	/**
	 * 本次正式目标工程完整正文SHA256。
	 */
	@Schema(description = "本次正式目标工程完整正文SHA256。")
	private String targetContentHash;

	/**
	 * APPLIED节点工时批准层已生效；CANCELLED永久取消且拒绝迟到采用。
	 */
	@Schema(description = "APPLIED节点工时批准层已生效；CANCELLED永久取消且拒绝迟到采用。")
	private String decision;

	/**
	 * 首次永久决定的实名人员主键；采用须与准备申请人不同。
	 */
	@Schema(description = "首次永久决定的实名人员主键；采用须与准备申请人不同。")
	private Long actorId;

	/**
	 * 首次永久决定的当地业务时间。
	 */
	@Schema(description = "首次永久决定的当地业务时间。")
	private LocalDateTime decidedAt;

	/**
	 * 永久采用或取消依据，最多1000字符，原文不可修改。
	 */
	@Schema(description = "永久采用或取消依据，最多1000字符，原文不可修改。")
	private String reason;

	/**
	 * 首次决定完整规范命令原文，最多16KiB。
	 */
	@Schema(description = "首次决定完整规范命令原文，最多16KiB。")
	private String commandSnapshot;

	/**
	 * 原规范命令UTF8 SHA256，同号异正文拒绝。
	 */
	@Schema(description = "原规范命令UTF8 SHA256，同号异正文拒绝。")
	private String commandFingerprint;

	/**
	 * 首次永久决定的完整规范回执正文，最多64KiB。
	 */
	@Schema(description = "首次永久决定的完整规范回执正文，最多64KiB。")
	private String receiptSnapshot;

	/**
	 * 规范回执原文字节SHA256，不含其自身摘要字段。
	 */
	@Schema(description = "规范回执原文字节SHA256，不含其自身摘要字段。")
	private String receiptFingerprint;

	/**
	 * 实际采用的目标工序任务主键；永久取消为空。
	 */
	@Schema(description = "实际采用的目标工序任务主键；永久取消为空。")
	private Long processId;

	/**
	 * 原稳定路线节点RN-id；永久取消为空。
	 */
	@Schema(description = "原稳定路线节点RN-id；永久取消为空。")
	private String routeNodeCode;

	/**
	 * 原主工序编码，必须与稳定节点共同核验；永久取消为空。
	 */
	@Schema(description = "原主工序编码，必须与稳定节点共同核验；永久取消为空。")
	private String processCode;

	/**
	 * 采用时原工单及完整目标派工范围原件，最多2MiB；取消为空。
	 */
	@Schema(description = "采用时原工单及完整目标派工范围原件，最多2MiB；取消为空。")
	private String sourceSnapshot;

	/**
	 * 目标范围原件UTF8 SHA256；取消为空。
	 */
	@Schema(description = "目标范围原件UTF8 SHA256；取消为空。")
	private String sourceFingerprint;

	/**
	 * 正式目标工程完整原件，最多8MiB；取消为空。
	 */
	@Schema(description = "正式目标工程完整原件，最多8MiB；取消为空。")
	private String bundleSnapshot;

	/**
	 * 实际批准的本稳定节点标准工时封套原文，最多64KiB；取消为空。
	 */
	@Schema(description = "实际批准的本稳定节点标准工时封套原文，最多64KiB；取消为空。")
	private String laborSnapshot;
}

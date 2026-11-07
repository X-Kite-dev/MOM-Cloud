package com.kitex.mom.mes.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 独立手工工单标准工时永久申请账；审批终态和原件不可覆写。
 */
@Data
public class MesManualLaborChangeEntity {
	/**
	 * 永久变更记录主键，出网保留文本精度。
	 */
	@JsonSerialize(using = ToStringSerializer.class)
	private Long id;

	/**
	 * 真实认证租户ID，所有精确查询同时限制实际工厂。
	 */
	@JsonSerialize(using = ToStringSerializer.class)
	private Long tenantId;

	/**
	 * 当前真实授权工厂编码。
	 */
	private String factoryCode;

	/**
	 * 组织内永久原申请号，取消后不复用。
	 */
	private String requestId;

	/**
	 * 原完整正文SHA256；组织与实名申请人另行精确绑定。
	 */
	private String commandFingerprint;

	/**
	 * 原完整申请正文，用于同号同事实核对；不直接输出。
	 */
	@JsonIgnore
	private String commandSnapshot;

	/**
	 * 原独立手工生产工单号。
	 */
	private String taskCode;

	/**
	 * 原手工工单主键，不允许同编号新对象替代。
	 */
	@JsonSerialize(using = ToStringSerializer.class)
	private Long taskId;

	/**
	 * 原唯一稳定节点实际工序任务主键。
	 */
	@JsonSerialize(using = ToStringSerializer.class)
	private Long processId;

	/**
	 * 原稳定路线节点RN-id。
	 */
	private String routeNodeCode;

	/**
	 * 实名申请人主键，必须与复核人不同。
	 */
	@JsonSerialize(using = ToStringSerializer.class)
	private Long applicantId;

	/**
	 * PENDING待审、APPROVED已批准、REJECTED已驳回、CANCELLED原号永久作废。
	 */
	private String status;

	/**
	 * 原工程身份、目标工序与完整派工范围的SHA256。
	 */
	private String sourceFingerprint;

	/**
	 * 申请时完整目标范围原件；仅服务端复核，不直接输出。
	 */
	@JsonIgnore
	private String sourceSnapshot;

	/**
	 * 原工单工程联合包SHA256，不修改原工程版本对。
	 */
	private String originalEngineeringHash;

	/**
	 * MDM正式目标工程发布主键。
	 */
	@JsonSerialize(using = ToStringSerializer.class)
	private Long releaseId;

	/**
	 * 目标工程UTF-8完整正文SHA256。
	 */
	private String contentHash;

	/**
	 * 目标完整工程原件；不直接输出。
	 */
	@JsonIgnore
	private String bundleSnapshot;

	/**
	 * 本稳定节点目标标准工时来源封套JSON，含原单位与批量。
	 */
	@JsonIgnore
	private String laborSnapshot;

	/**
	 * 原申请业务依据。
	 */
	private String reason;

	/**
	 * 服务器首次接受申请或取消原号时刻。
	 */
	private LocalDateTime proposedAt;

	/**
	 * 实际异人复核者主键；未复核为空。
	 */
	@JsonSerialize(using = ToStringSerializer.class)
	private Long reviewerId;

	/**
	 * 异人批准或驳回依据。
	 */
	private String reviewReason;

	/**
	 * 实际完成异人复核时刻；未复核为空。
	 */
	private LocalDateTime reviewedAt;

	/**
	 * 返回时从原包与该行目标原件投影的小型差异，不写入永久账、不接受客户端回写。
	 */
	private com.kitex.mom.mes.dto.MesManualLaborChangeDTO.Comparison comparison;

}

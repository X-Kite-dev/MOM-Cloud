package com.kitex.mom.mes.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import java.time.LocalDateTime;

/** 永久原号及批准证据；不替换原工单、工序或派工工程快照。 */
@Data
public class MesInFlightSopChangeEntity {
	/** 永久变更记录ID，出网保持文本精度。 */
	@JsonSerialize(using = ToStringSerializer.class)
	private Long id;
	/** 真实认证租户ID；所有精确查询同时核对工厂。 */
	@JsonSerialize(using = ToStringSerializer.class)
	private Long tenantId;
	/** 实际授权工厂编码。 */
	private String factoryCode;
	/** 同组织内不可复用的原申请号。 */
	private String requestId;
	/** 绑定组织、申请人和原申请正文的SHA256，防止异载荷重投。 */
	private String commandFingerprint;
	/** 原已下达生产工单号，不重绑其他工单。 */
	private String taskCode;
	/** 原实际工序任务ID，指向唯一稳定节点。 */
	@JsonSerialize(using = ToStringSerializer.class)
	private Long processId;
	/** 原路线稳定节点编码RN-id，不以重复主工序替代。 */
	private String routeNodeCode;
	/** 原实名申请人ID，必须与复核人不同。 */
	@JsonSerialize(using = ToStringSerializer.class)
	private Long applicantId;
	/** PENDING待复核、APPROVED已批准生效、REJECTED已驳回。 */
	private String status;
	/** 申请时原包身份及工序/派工范围快照SHA256。 */
	private String sourceFingerprint;
	/** 申请时完整原执行范围JSON，复核批准前再次精确核对。 */
	private String sourceSnapshot;
	/** 目标正式生产工程发布ID。 */
	@JsonSerialize(using = ToStringSerializer.class)
	private Long releaseId;
	/** 目标工程原始UTF-8正文SHA256。 */
	private String contentHash;
	/** MDM服务端取回的目标完整原件，禁止客户端替换正文。 */
	private String bundleSnapshot;
	/** 仅本节点的批准正式SOP冻结证明，不改写其他工程内容。 */
	private String sopSnapshot;
	/** 申请人填写的原变更依据。 */
	private String reason;
	/** 服务器保存原申请的业务时刻。 */
	private LocalDateTime proposedAt;
	/** 实名异人复核人ID；待复核时为空。 */
	@JsonSerialize(using = ToStringSerializer.class)
	private Long reviewerId;
	/** 异人批准或驳回依据；未复核时为空。 */
	private String reviewReason;
	/** 实际复核完成时刻；未复核时为空。 */
	private LocalDateTime reviewedAt;
}

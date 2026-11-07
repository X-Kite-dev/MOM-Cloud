package com.kitex.mom.mes.entity;

import lombok.Data;
import java.time.LocalDateTime;

/** 未受理原申请的永久取消墓碑；同号迟到提交不能重新激活。 */
@Data
public class MesInFlightSopChangeCancellationEntity {
	/** 原申请所属真实认证租户ID。 */
	private Long tenantId;
	/** 原申请所属实际授权工厂。 */
	private String factoryCode;
	/** 永久作废的未受理原申请号，拒绝同号迟到提交。 */
	private String requestId;
	/** 绑定组织、申请人及完整原载荷的SHA256。 */
	private String commandFingerprint;
	/** 原实名申请人ID；仅该人可恢复或作废该原号。 */
	private Long applicantId;
	/** 服务器确认永久作废的业务时刻。 */
	private LocalDateTime cancelledAt;
}

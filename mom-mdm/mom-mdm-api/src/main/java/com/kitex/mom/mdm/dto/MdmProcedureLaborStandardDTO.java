package com.kitex.mom.mdm.dto;

import lombok.Data;

/** 工程联合包批准的工序时间标准原件；不代表实际工时、人数或劳动成本。 */
@Data
public class MdmProcedureLaborStandardDTO {
	/** 标准来源封套协议；当前为MDM_PROCEDURE_LABOR_STANDARD_V1，未知版本拒绝。 */
	private String schemaVersion;
	/** 批准时同组织真实工序标准的原始UTF-8 JSON，含原单位、基本批量、记录身份及更新时间。 */
	private String sourceSnapshot;
	/** sourceSnapshot原字节SHA256；不能用当前主数据重新计算替代原件。 */
	private String sourceFingerprint;
}

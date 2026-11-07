package com.kitex.mom.mes.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 首次受理的不可变设备来源；仅提供插入和读取，不提供更新或删除映射。
 */
@Data
public class MesEquipmentOeeBatchEntity {
	/**
	 * 实际租户主键。
	 */
	private Long tenantId;
	/**
	 * 经过授权的实际工厂编码。
	 */
	private String factoryCode;
	/**
	 * 实际组织内永久唯一原批次号。
	 */
	private String batchId;
	/**
	 * 本批唯一实际设备编码。
	 */
	private String deviceCode;
	/**
	 * 来源种类：SOURCE_IMPORT为有原引用的操作员导入，SIMULATION为明确仿真。
	 */
	private String sourceKind;
	/**
	 * 闭合统计窗口的UTC开始时刻。
	 */
	private String periodStart;
	/**
	 * 闭合统计窗口的UTC结束时刻，不含端点。
	 */
	private String periodEnd;
	/**
	 * 完整原始来源JSON字符串；按UTF8原文冻结，不用当前配置重建。
	 */
	private String sourceJson;
	/**
	 * 不可变原文的SHA256指纹。
	 */
	private String fingerprint;
	/**
	 * 服务器当前实名操作者主键文本。
	 */
	private String actorId;
	/**
	 * 服务器首次受理UTC时间，数据库按UTC无时区列保存。
	 */
	private LocalDateTime acceptedAt;
}

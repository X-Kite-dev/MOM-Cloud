package com.kitex.mom.mes.entity;

import lombok.Data;

/**
 * 标签原件及打印意图的不可变持久投影，不作为客户端可回写合同。
 */
@Data
public class MesProductionLabelEntity {
	/**
	 * 发放原件或打印意图的内部审计主键。
	 */
	private Long id;
	/**
	 * 实际认证租户主键。
	 */
	private Long tenantId;
	/**
	 * 经过授权的实际工厂编码。
	 */
	private String factoryCode;
	/**
	 * 首次办理本原件的实名操作者主键。
	 */
	private Long actorId;
	/**
	 * 首次生成并保持的稳定原请求号；未知结果按此号核对。
	 */
	private String requestId;
	/**
	 * 原派工主键。
	 */
	private Long arrangeId;
	/**
	 * 意图对应的标签发放原件主键；发放行自身为空。
	 */
	private Long issueId;
	/**
	 * 同一原件内的连续打印意图序号；发放行为空。
	 */
	private Long ordinalNo;
	/**
	 * 首次服务器受理的完整原文，不允许更新或删除。
	 */
	private String payload;
	/**
	 * 不可变原文的SHA256指纹。
	 */
	private String fingerprint;
	/**
	 * 冻结来源原文指纹。
	 */
	private String sourceFingerprint;
	/**
	 * 冻结模板摘要指纹；发放记录使用。
	 */
	private String templateFingerprint;
}

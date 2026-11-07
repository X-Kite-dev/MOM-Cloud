package com.kitex.mom.mes.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接收时不可变的标准工时采用来源；从不重写原工程、计划或实际工时。
 */
@Data
public class MesManualLaborAdoptionEntity {
	/**
	 * 首次接收标准采用账主键，出网为文本。
	 */
	@JsonSerialize(using = ToStringSerializer.class)
	private Long id;

	/**
	 * 真实租户主键。
	 */
	@JsonSerialize(using = ToStringSerializer.class)
	private Long tenantId;

	/**
	 * 真实工厂编码。
	 */
	private String factoryCode;

	/**
	 * 原独立手工工单号。
	 */
	private String taskCode;

	/**
	 * 原独立手工工单主键。
	 */
	@JsonSerialize(using = ToStringSerializer.class)
	private Long taskId;

	/**
	 * 原稳定节点工序任务主键。
	 */
	@JsonSerialize(using = ToStringSerializer.class)
	private Long processId;

	/**
	 * 原稳定节点RN-id。
	 */
	private String routeNodeCode;

	/**
	 * 实际接收的原派工主键，组织内唯一冻结一次。
	 */
	@JsonSerialize(using = ToStringSerializer.class)
	private Long arrangeId;

	/**
	 * 实际采用的批准层主键；采用原工程或历史未知来源时为空。
	 */
	@JsonSerialize(using = ToStringSerializer.class)
	private Long changeId;

	/**
	 * 原工程联合包UTF-8正文SHA256。
	 */
	private String originalEngineeringHash;

	/**
	 * 实际采用来源的正式工程发布主键。
	 */
	@JsonSerialize(using = ToStringSerializer.class)
	private Long releaseId;

	/**
	 * 实际采用来源的完整工程包SHA256。
	 */
	private String contentHash;

	/**
	 * 实际采用的标准工时封套原件；历史未知为空。
	 */
	private String laborSnapshot;

	/**
	 * 实际采用标准工时sourceSnapshot的SHA256；历史未知为空。
	 */
	private String sourceFingerprint;

	/**
	 * 同事务首次接受该派工时冻结采用来源的服务器时刻。
	 */
	private LocalDateTime receivedAt;

}

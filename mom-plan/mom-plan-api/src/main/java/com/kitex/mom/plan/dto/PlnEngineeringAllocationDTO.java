package com.kitex.mom.plan.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 工程变更前的PLAN实际分配观察。原记录来自PLAN、来源映射来自MES；
 * 观察摘要只用于核对，既不是永久原件号，也不是可阻止并发变化的版本栅栏。
 */
public final class PlnEngineeringAllocationDTO {

	private PlnEngineeringAllocationDTO() {
	}

	/**
	 * 实际保存行的文本投影；缺失旧字段保留null，原件不从今日主数据补齐。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Fact(
			/** ISSUE/BATCH/ALLOCATION/TASK/OPERATION来源类别。 */String kind,
			/** 原表主键十进制文本。 */String id,
			/** 该行原业务编码，可为空。 */String code,
			/** 原计划行ID文本，可为空。 */String issueId,
			/** 原批次ID文本，可为空。 */String batchId,
			/** 原稳定节点；旧未留存节点不可回填。 */String routeNodeCode,
			/** 原业务状态，可为空。 */String status,
			/** 原逻辑删除标记；未知不当作有效。 */String delFlag,
			/** 原资源类型，可为空。 */String resourceType,
			/** 原工位/产线/车间资源编码，可为空。 */String resourceCode,
			/** 原设备编码，可为空。 */String deviceCode,
			/** 原员工编码，可为空。 */String employeeCode,
			/** 原联合工装编码文本，不重新拆分或解析身份。 */String toolingCodes,
			/** 原计划开始当地时间文本；不转浏览器时区。 */String startTime,
			/** 原计划结束当地时间文本。 */String endTime,
			/** 原计划/分配数量十进制文本，单位沿原件，不推断。 */String quantity,
			/** 原保存行的完整JSON文本；禁止浏览器重新数值化长ID或数量。 */String snapshotJson) {
	}

	/**
	 * 完整有界读取结果；即使映射一致，仍没有任何变更执行资格。
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Observation(
			/** 固定PLAN_ENGINEERING_ALLOCATION_OBSERVATION_V1。 */String schemaVersion,
			/** 当前认证租户十进制文本。 */String tenantId,
			/** 已通过MDM实际读授权的工厂。 */String factoryCode,
			/** MES原工单主键十进制文本。 */String taskId,
			/** MES原工单号。 */String taskCode,
			/** MES原工单产品编码。 */String productCode,
			/** MES原冻结工程发布ID文本。 */String sourceReleaseId,
			/** MES原冻结工程包SHA256。 */String sourceEngineeringHash,
			/** 本次观察完成UTC时刻。 */Instant observedAt,
			/** 双读保持一致的MES来源映射摘要。 */String mappingFingerprint,
			/** 本次有序原记录及映射的SHA256，不具有锁效力。 */String observationFingerprint,
			/** 所有MES原分配是否逐项匹配PLAN实际来源。 */boolean mappingMatched,
			/** 全部命中原记录，包括同批其他计划及逻辑删除历史。 */List<Fact> facts,
			/** 已读事实的缺口或冲突；为空也不代表可以生效。 */List<String> dataGaps,
			/** 本接口始终false。 */boolean executable,
			/** 当前无跨域版本栅栏，始终null。 */String versionFence) {
	}
}

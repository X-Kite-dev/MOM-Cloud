package com.kitex.mom.mes.dto;

import java.util.List;

/**
 * 设备OEE的来源、分量及回执合同；设备物理时间不混用人工工时，产出良率不冒充一次合格率。
 */
public final class MesEquipmentOeeDTO {
	private MesEquipmentOeeDTO() {
	}

	/**
	 * 接收完整OEE来源原文的导入命令。
	 *
	 * @param sourceJson 完整原始来源JSON字符串；按UTF8原文冻结，不用当前配置重建。
	 */
	public record ImportCommand(String sourceJson) {
	}

	/**
	 * 原计划生产区间。
	 *
	 * @param sourceId 提供方原来源引用，区间或产出记录内必须唯一。
	 * @param start    区间开始UTC时刻，含该端点。
	 * @param end      区间结束UTC时刻，不含该端点。
	 */
	public record Window(String sourceId, String start, String end) {
	}

	/**
	 * 设备物理状态观察区间。
	 *
	 * @param sourceId 提供方原来源引用，区间或产出记录内必须唯一。
	 * @param start    区间开始UTC时刻，含该端点。
	 * @param end      区间结束UTC时刻，不含该端点。
	 * @param state    设备观察状态：RUN运行、STOP停止、UNKNOWN未知。
	 */
	public record Observation(String sourceId, String start, String end, String state) {
	}

	/**
	 * 同设备、产品、节点和原单位的实际产出归属。
	 *
	 * @param sourceId        提供方原来源引用，区间或产出记录内必须唯一。
	 * @param eventTime       产出事实的UTC事件时刻，必须属于本批闭合时间窗。
	 * @param taskCode        真实生产工单编码，限定实际租户和工厂。
	 * @param produceTaskCode 真实工序任务编码，不以主工序编码替代。
	 * @param arrangeCode     原派工业务编码。
	 * @param deviceCode      本批唯一实际设备编码。
	 * @param productCode     本批唯一产品编码。
	 * @param routeNodeCode   冻结路线内稳定节点编码，区分重复主工序。
	 * @param unit            原数量单位；不同单位不直接汇总。
	 * @param totalQuantity   原单位总产出量的精确十进制文本。
	 * @param goodQuantity    原单位合格产出量文本；是产出良率依据，不是一次合格数。
	 */
	public record Output(String sourceId, String eventTime, String taskCode, String produceTaskCode,
	                     String arrangeCode, String deviceCode, String productCode, String routeNodeCode,
	                     String unit, String totalQuantity, String goodQuantity) {
	}

	/**
	 * 设备OEE来源批次及完整性声明。
	 *
	 * @param contract          固定协议版本标识。
	 * @param batchId           实际组织内永久唯一原批次号。
	 * @param sourceKind        来源种类：SOURCE_IMPORT为有原引用的操作员导入，SIMULATION为明确仿真。
	 * @param providerCode      来源提供方编码，仅表示原提供方声明，不代表网关认证。
	 * @param originReference   可核对的原文件、系统或实验记录引用。
	 * @param originTimezone    原来源IANA时区标签，实际计算时刻仍使用UTC。
	 * @param confirmation      对应来源种类的明确导入或仿真确认声明。
	 * @param deviceCode        本批唯一实际设备编码。
	 * @param productCode       本批唯一产品编码。
	 * @param routeNodeCode     冻结路线内稳定节点编码，区分重复主工序。
	 * @param unit              原数量单位；不同单位不直接汇总。
	 * @param periodStart       闭合统计窗口的UTC开始时刻。
	 * @param periodEnd         闭合统计窗口的UTC结束时刻，不含端点。
	 * @param calendarVersion   本设备原计划日历版本。
	 * @param calendarComplete  计划区间是否完整；未知不能按零计划时间处理。
	 * @param idealCycleVersion 该产品、节点、原单位的理想周期版本。
	 * @param idealCycleSeconds 每一原单位的理想加工秒数，使用精确十进制文本。
	 * @param outputComplete    统计窗口内产出归属是否完整。
	 * @param plannedWindows    全部计划生产区间，使用区间并集核分母。
	 * @param observations      完整设备状态区间，不能用人工工时替代。
	 * @param outputs           全部原单位产出归属事实。
	 */
	public record Batch(String contract, String batchId, String sourceKind, String providerCode,
	                    String originReference, String originTimezone, String confirmation,
	                    String deviceCode, String productCode, String routeNodeCode, String unit,
	                    String periodStart, String periodEnd, String calendarVersion, Boolean calendarComplete,
	                    String idealCycleVersion, String idealCycleSeconds, Boolean outputComplete,
	                    List<Window> plannedWindows, List<Observation> observations, List<Output> outputs) {
	}

	/**
	 * 指标状态与可复算的原分子、分母。
	 *
	 * @param status      计算状态或可用性说明；未知、无分母与有效结果分开。
	 * @param numerator   该指标的原分子文本，未知时可空。
	 * @param denominator 该指标的原分母文本，未知或无有效分母时可空。
	 * @param percent     有效指标百分数文本；不可用时为空，不钳制超过100%的矛盾来源。
	 */
	public record Rate(String status, String numerator, String denominator, String percent) {
	}

	/**
	 * OEE时间、产量及四项计算分量。
	 *
	 * @param plannedSeconds    计划生产区间并集秒数文本；未知为空。
	 * @param runSeconds        计划窗口内实际运行秒数文本；覆盖不完整时为空。
	 * @param stopSeconds       计划窗口内实际停止秒数文本；覆盖不完整时为空。
	 * @param totalQuantity     原单位总产出量的精确十进制文本。
	 * @param goodQuantity      原单位合格产出量文本；是产出良率依据，不是一次合格数。
	 * @param idealTotalSeconds 总产出量乘原单位理想周期所得的精确秒数文本。
	 * @param availability      可用率：运行秒数除计划生产秒数。
	 * @param performance       性能率：总产出理想秒数除实际运行秒数。
	 * @param quality           产出良率：合格量除总产出量，不代表一次合格率。
	 * @param oee               设备综合效率：合格产出理想秒数除计划生产秒数。
	 */
	public record Metrics(String plannedSeconds, String runSeconds, String stopSeconds,
	                      String totalQuantity, String goodQuantity, String idealTotalSeconds,
	                      Rate availability, Rate performance, Rate quality, Rate oee) {
	}

	/**
	 * 来源批次的计算结果及明确缺口。
	 *
	 * @param contract 固定协议版本标识。
	 * @param source   该结果关联的完整冻结来源。
	 * @param status   计算状态或可用性说明；未知、无分母与有效结果分开。
	 * @param metrics  各分量及其真实分子、分母；缺数据的值保持可空。
	 * @param dataGaps 阻止完整计算的缺口，不能由占位数据补齐。
	 */
	public record Measurement(String contract, Batch source, String status, Metrics metrics, List<String> dataGaps) {
	}

	/**
	 * 服务器原件受理或只读预览结果。
	 *
	 * @param factoryCode 经过授权的实际工厂编码。
	 * @param fingerprint 不可变原文的SHA256指纹。
	 * @param actorId     服务器当前实名操作者主键文本。
	 * @param acceptedAt  服务器首次受理的UTC时刻。
	 * @param persisted   是否已确认原件永久保存；预览不宣称已保存。
	 * @param measurement 本原件的计算结果及缺口。
	 */
	public record Saved(String factoryCode, String fingerprint, String actorId, String acceptedAt,
	                    boolean persisted, Measurement measurement) {
	}

	/**
	 * OEE目录中的不可变来源摘要。
	 *
	 * @param batchId     实际组织内永久唯一原批次号。
	 * @param deviceCode  本批唯一实际设备编码。
	 * @param sourceKind  来源种类：SOURCE_IMPORT为有原引用的操作员导入，SIMULATION为明确仿真。
	 * @param periodStart 闭合统计窗口的UTC开始时刻。
	 * @param periodEnd   闭合统计窗口的UTC结束时刻，不含端点。
	 * @param acceptedAt  服务器首次受理的UTC时刻。
	 * @param fingerprint 不可变原文的SHA256指纹。
	 */
	public record Summary(String batchId, String deviceCode, String sourceKind, String periodStart,
	                      String periodEnd, String acceptedAt, String fingerprint) {
	}

	/**
	 * OEE原件分页目录。
	 *
	 * @param total   目录总记录数文本，避免JavaScript长整数损失。
	 * @param records 当前页的原件摘要列表。
	 */
	public record Page(String total, List<Summary> records) {
	}
}

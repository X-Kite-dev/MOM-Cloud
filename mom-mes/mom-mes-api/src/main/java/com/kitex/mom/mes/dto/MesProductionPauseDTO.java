package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 生产暂停原因分析的独立只读合同；不把暂停等同设备停机或人员损失。
 */
public final class MesProductionPauseDTO {

	private MesProductionPauseDTO() {
	}

	/**
	 * 继承原工时的实际范围条件；新增筛选只用于完整统计后的原区间下钻。
	 */
	@Getter
	@Setter
	public static class Query extends MesProductionTimeDTO.Query {

		/**
		 * 可空；服务端原因分组的64位SHA256键，仅筛选明细，不是组织访问凭据。
		 */
		private String pauseReasonKey;
	}

	/**
	 * 同类资源内先求并集的时间，所有秒数保留微秒精度十进制文本。
	 *
	 * @param coveredSeconds 全部有效区间并集秒数；无有效来源时为null
	 * @param closedSeconds  已闭合区间并集秒数；无已闭合来源时为null
	 * @param ongoingSeconds 进行中区间截至观察时刻的并集秒数；无此来源时为null
	 * @param sourceCount    本范围全部来源数，含无效来源，最多20000
	 * @param excludedCount  未计入时间的无效来源数
	 * @param ongoingCount   有效且进行中的来源数
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Metrics(String coveredSeconds, String closedSeconds, String ongoingSeconds,
	                      int sourceCount, int excludedCount, int ongoingCount) {
	}

	/**
	 * 同一类型与原原因的完整分组；分组间可能重叠，不得相加冒充独占时长。
	 *
	 * @param key        类型及原原因原文的指纹，同类型下null与空白仍分别保留
	 * @param kind       PAUSE为派工暂停，WORKPIECE_PAUSE为实际工件/批次暂停
	 * @param reason     可空的原始原因，不从今日字典回填或改写
	 * @param classified 是否有明确分类；通用人工暂停不认作异常原因
	 * @param metrics    本组完整来源的时间及条数
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Reason(String key, String kind, String reason, boolean classified, Metrics metrics) {
	}

	/**
	 * 归一化范围；当地ISO时间保留9位小数，避免全局秒级序列化丢失精度。
	 *
	 * @param startTime       开始含的当地ISO时间，无时区转换
	 * @param endTime         结束不含的当地ISO时间，无时区转换
	 * @param taskCode        可空的原工单编码
	 * @param produceTaskCode 可空的原工序任务编码
	 * @param arrangeCode     可空的原派工编码
	 * @param workshopCode    可空的原车间筛选
	 * @param shiftCode       可空的原班次筛选
	 * @param identifier      可空的原实物标识，保留原文
	 * @param identifierType  与实物标识成对的原类型
	 * @param groupBy         沿原工时的合法分组维度
	 * @param detailPage      本次明细页码，从1开始
	 * @param detailSize      每页来源数，1至200
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Scope(String startTime, String endTime, String taskCode, String produceTaskCode,
	                    String arrangeCode, String workshopCode, String shiftCode, String identifier,
	                    String identifierType,
	                    String groupBy, int detailPage, int detailSize) {
	}

	/**
	 * 同实际租户工厂的原暂停来源；时间保留完整当地ISO文本，不经秒级全局格式器。
	 *
	 * @param sourceType      原来源类型
	 * @param sourceId        原来源精确ID文本
	 * @param taskCode        可空的原工单编码，缺失则来源不计时
	 * @param produceTaskCode 可空的原工序任务编码
	 * @param arrangeCode     可空的原派工编码，缺失则来源不计时
	 * @param workshopCode    可空的原派工车间编码
	 * @param shiftCode       可空的原派工班次编码
	 * @param deviceCode      可空的原设备编码，不据此推断物理停机
	 * @param workerCode      可空的原作业员工号，不据此分摊损失人时
	 * @param identifier      可空的原工件或批次标识
	 * @param identifierType  可空的原标识类型
	 * @param kind            PAUSE为派工暂停，WORKPIECE_PAUSE为工件暂停
	 * @param startTime       可空的原开始当地ISO时间，保留9位小数
	 * @param endTime         可空的原结束当地ISO时间，null还须结合原状态判断
	 * @param state           可空的原状态，OPEN/CLOSED等原文保持
	 * @param eligible        可空的原资格判断，只有明确true才可计时
	 * @param reason          可空的原原因，不借今日字典回填
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Source(String sourceType, String sourceId, String taskCode, String produceTaskCode,
	                     String arrangeCode, String workshopCode, String shiftCode, String deviceCode,
	                     String workerCode,
	                     String identifier, String identifierType, String kind, String startTime, String endTime,
	                     String state, Boolean eligible, String reason) {
	}

	/**
	 * 当前统计窗内原区间；无效来源仍显示，不伪造有效时间。
	 *
	 * @param fact        原始来源及完整精度时间
	 * @param windowStart 可空的裁剪开始当地ISO文本，只有有效区间提供
	 * @param windowEnd   可空的裁剪结束当地ISO文本，进行中暂计至观察时刻
	 * @param seconds     可空的有效微秒换算秒文本，未知不填零
	 * @param included    是否计入有效时长
	 * @param ongoing     是否有效且仍进行中
	 * @param issue       可空的来源缺口或未分类原因
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Interval(Source fact, String windowStart, String windowEnd, String seconds,
	                       boolean included, boolean ongoing, String issue) {
	}

	/**
	 * 当前实际授权租户工厂的暂停分析，不修改任何事实。
	 *
	 * @param contract       固定MES_PRODUCTION_PAUSE_V1
	 * @param factoryCode    当前经实际授权验证的工厂编码
	 * @param scope          归一化的原工时范围，保持时间窗与工单/工件身份
	 * @param pauseReasonKey 本次精确筛选的原原因键；null表示全部暂停明细
	 * @param observedAt     服务端当地观察时刻；进行中时长仅为暂计
	 * @param dispatch       派工暂停的资源内并集，与工件口径不合并
	 * @param workpiece      工件暂停的资源内并集，含原标识类型与标识号
	 * @param reasons        原完整来源集合的原因分组，不受明细分页和原因筛选影响
	 * @param details        当前原因与页码的原区间，含拒绝计时的来源
	 * @param detailTotal    当前原因条件命中的完整来源条数
	 * @param dataGaps       来源缺口与不完整事实说明，不据此推断无异常
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record View(String contract, String factoryCode, Scope scope, String pauseReasonKey,
	                   String observedAt, Metrics dispatch, Metrics workpiece, List<Reason> reasons,
	                   List<Interval> details, int detailTotal, List<String> dataGaps) {
	}
}

package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** 当前实名工站在制派工的轻量选择摘要，不返回完整工程和执行策略正文。 */
@Data
@Accessors(chain = true)
public class MesRunningDispatchVO {

	/** 真实派工身份，始终以十进制文本传输。 */
	@Schema(description = "真实派工ID文本")
	private String id;

	/** 真实派工单号。 */
	@Schema(description = "真实派工单号")
	private String code;

	/** 原生产工单号。 */
	@Schema(description = "原生产工单号")
	private String taskCode;

	/** 原工序任务号，不以主工序编码代替。 */
	@Schema(description = "原工序任务号")
	private String produceTaskCode;

	/** 生产产品编码。 */
	@Schema(description = "生产产品编码")
	private String matCode;

	/** 生产产品名称快照。 */
	@Schema(description = "生产产品名称")
	private String matName;

	/** 原工序显示名称。 */
	@Schema(description = "原工序名称")
	private String routeName;

	/** 本派工原需求/配额，以原基本单位的精确十进制文本传输，未知为空。 */
	@Schema(description = "本派工需求/配额十进制文本")
	private String noticeNum;

	/** 本派工实际GOOD累计，以精确十进制文本传输，未知为空。 */
	@Schema(description = "本派工实际GOOD累计十进制文本")
	private String completeNum;

	/** 该任务实际日期班次编码。 */
	@Schema(description = "实际班次编码")
	private String shiftCode;

	/** 该任务实际班次名称。 */
	@Schema(description = "实际班次名称")
	private String shiftName;

	/** 实际接收工站，必须等于当前选中工站。 */
	@Schema(description = "实际接收工站")
	private String actualWorksiteCode;

	/** 只返回PRODUCT生产中或PAUSE暂停中。 */
	@Schema(description = "PRODUCT生产中或PAUSE暂停中")
	private String status;

	/** 服务端核定的实际租户ID文本。 */
	@Schema(description = "实际租户ID文本")
	private String tenantId;

	/** 服务端核定的实际工厂编码。 */
	@Schema(description = "实际工厂编码")
	private String factoryCode;
}

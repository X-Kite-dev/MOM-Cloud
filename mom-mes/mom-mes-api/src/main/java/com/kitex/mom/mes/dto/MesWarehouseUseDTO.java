package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/**
 * 合格报工内部投料耗用上下文。单位和数量必须再核实际原报告，不是客户端扣料命令。
 */
@Data
@Accessors(chain = true)
public class MesWarehouseUseDTO {

    /**
     * 当前真实合格报告主键，不使用独立生产operation或不良报告主键。
     */
    @Schema(description = "当前合格报告ID")
    private Long reportId;

    /**
     * 当前合格报工的原请求号，成功重放不得再次耗料。
     */
    @Schema(description = "当前合格报工原请求号")
    private String reportRequestId;

    /**
     * 服务器从实际报告确定的原派工；工单和工序归属由该派工核定。
     */
    @Schema(description = "实际原派工编码")
    private String arrangeCode;

    /**
     * 当前GOOD原数量，禁止填写GOOD+NG总加工量或使用舍入后的量。
     */
    @Schema(description = "当前合格原数量")
    private BigDecimal reportNum;

    /**
     * 原派工所属工序编码，只解析该工序的完整需求集合。
     */
    @Schema(description = "原工序编码")
    private String routeCode;

    /**
     * 原父工单的路线编码，不按当前主数据替换。
     */
    @Schema(description = "原工艺路线编码")
    private String processRouteCode;

    /**
     * 原父工单冻结的完整路线JSON，耗用前与实际原快照逐字核对。
     */
    @Schema(description = "原工单路线快照")
    private String snapshot;

    /**
     * 原父工单冻结BOM及主替代策略，不能提交前端临时策略。
     */
    @Schema(description = "原工单BOM快照")
    private String bomSnapshot;

    /**
     * 服务器实际工厂；此值只能用于与认证工厂核对，不能授予跨厂权限。
     */
    @Schema(description = "实际工厂编码")
    private String factoryCode;

	/** 可选的单件精确用料原文；随报工永久号保存，内部耗用前再核真实原来源。 */
	@Schema(description = "本件用料选择原文")
	private String materialSelectionJson;

}

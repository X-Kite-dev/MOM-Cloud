package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.math.BigDecimal;

/**
 * 工序列表
 *
 * @author kitex
 * @since 2025-05-19
 */
@Data
public class MdmProcessRouteWorkingDTO {
    /** 返工工艺转换也必须保留实际用料，最终由返工补料方案冻结。 */
    private java.util.List<ProcessProcedureDTO.ProcessProcedureMatDTO> mdmProcessProcedureMatList;
    /** 保留工单冻结SOP引用；null为历史未声明，空列表才表示明确无附件。 */
    private java.util.List<MesSopReferenceDTO> mdmProcessProcedureSopList;

    /**
     * 工艺路线编码
     */
    @Schema(description = "工艺路线编码")
    private String routeCode;

    /**
     * 工艺路线名称
     */
    @Schema(description = "工艺路线名称")
    private String routeName;

    /**
     * 工序编码
     */
    @Schema(description = "工序编码")
    private String processCode;

    /**
     * 工序名称
     */
    @Schema(description = "工序名称")
    private String processName;

    /**
     * 顺序
     */
    @Schema(description = "顺序")
    private Integer orderNum;

    /**
     * 前置工序编码
     */
    @Schema(description = "前置工序编码")
    private String[] preRouteCode;

    @Schema(description = "后置工序编码")
    private String[] nextRouteCode;

    private Boolean overlapEnabled;
    private BigDecimal transferBatchQuantity;
    private Boolean timeCheck;
    private BigDecimal timeVal;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;

    /**
     * 类型 一物一码/数量
     */
    @Schema(description = "类型 一物一码/数量")
    private String type;

    @Schema(description = "工序顺序")
    private Integer routeOrder;

    @Schema(description = "是否最后工序")
    private Boolean lastRoute;

    /**
     * 工作中心列表
     */
    @Schema(description = "工作中心列表")
    private List<MdmProcessRouteWorkCenterDTO> mdmProcessProcedureWorkList;

    private String worksiteCodes;

    private String worksiteNames;

    private String isInspection;

    private String inspectionRuleCode;
}

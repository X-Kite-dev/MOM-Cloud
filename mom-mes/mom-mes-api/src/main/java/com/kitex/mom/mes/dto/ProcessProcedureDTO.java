package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ProcessProcedureDTO {

    @Schema(description = "主键ID")
    private String id;

    @Schema(description = "工序编码")
    private String processCode;

    @Schema(description = "工序名称")
    private String processName;

    @Schema(description = "排序号")
    private Integer orderNum;

    @Schema(description = "前置工序编码")
    private String preRouteCode;

    @Schema(description = "车间编码")
    private String workshopCode;

    @Schema(description = "车间名称")
    private String workshopName;

    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;

    @Schema(description = "类型")
    private String type;

    @Schema(description = "工艺路线编码")
    private String routeCode;

    @Schema(description = "工艺路线名称")
    private String routeName;

    @Schema(description = "工位编码")
    private String worksiteCodes;

    @Schema(description = "工位名称")
    private String worksiteNames;

    @Schema(description = "工艺路线顺序")
    private Integer routeOrder;

    @Schema(description = "工序工位列表")
    private List<ProcessProcedureWorkDTO> mdmProcessProcedureWorkList;

    @Schema(description = "工序物料列表")
    private List<ProcessProcedureMatDTO> mdmProcessProcedureMatList;

    @Schema(description = "工序SOP列表")
    private List<ProcessProcedureSopDTO> mdmProcessProcedureSopList;

    @Schema(description = "工序模具列表")
    private List<ProcessProcedureMoldDTO> mdmProcessProcedureMoldList;

    @Data
    public static class ProcessProcedureWorkDTO {

        @Schema(description = "主键ID")
        private String id;

        @Schema(description = "工艺路线编码")
        private String routeCode;

        @Schema(description = "工序编码")
        private String processCode;

        @Schema(description = "工位编码")
        private String workCode;

        @Schema(description = "工位名称")
        private String workName;

        @Schema(description = "工厂编码 - 数据所属的工厂编码")
        private String factoryCode;
    }

    @Data
    public static class ProcessProcedureMatDTO {
        @Schema(description = "主键ID")
        private String id;

        @Schema(description = "工艺路线编码")
        private String routeCode;

        @Schema(description = "工序编码")
        private String processCode;

        @Schema(description = "物料编码")
        private String materialCode;

        @Schema(description = "物料名称")
        private String materialName;

        @Schema(description = "单位")
        private String unit;

        @Schema(description = "数量")
        private BigDecimal quantity;
    }

    @Data
    public static class ProcessProcedureSopDTO {
        @Schema(description = "主键ID")
        private String id;

        @Schema(description = "租户ID")
        private String tenantId;

        @Schema(description = "工艺路线编码")
        private String routeCode;

        @Schema(description = "工序编码")
        private String processCode;

        @Schema(description = "文件名称")
        private String name;

        @Schema(description = "文件名")
        private String fileName;

        @Schema(description = "文件URL")
        private String url;

        @Schema(description = "工厂编码 - 数据所属的工厂编码")
        private String factoryCode;
    }

    @Data
    public static class ProcessProcedureMoldDTO {
        @Schema(description = "模具编码")
        private String moldCode;

        @Schema(description = "模具名称")
        private String moldName;

        @Schema(description = "规格")
        private String spec;

        @Schema(description = "型号")
        private String model;
    }
} 
package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesAndonHandleConfigStaffEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
public class MesAndonHandleConfigVO {

    private Long id;

    /**
     * 流程编码
     */
    @Schema(description = "流程编码")
    private String code;

    /**
     * 异常类型编码
     */
    @Schema(description = "异常类型编码")
    private String typeCode;

    /**
     * 异常类型名称
     */
    @Schema(description = "异常类型名称")
    private String typeName;

    /**
     * 车间编码
     */
    @Schema(description = "车间编码")
    private String workshopCode;

    /**
     * 车间名称
     */
    @Schema(description = "车间名称")
    private String workshopName;

    /**
     * 产线编号
     */
    @Schema(description = "产线编号")
    private String lineCode;

    /**
     * 产线名称
     */
    @Schema(description = "产线名称")
    private String lineName;

    /**
     * 班次编号
     */
    @Schema(description = "班次编号")
    private String shiftCode;

    /**
     * 班次名称
     */
    @Schema(description = "班次名称")
    private String shiftName;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    private List<MesAndonHandleConfigStaffEntity> generalList;

    private List<MesAndonHandleConfigStaffEntity> seriousList;
}

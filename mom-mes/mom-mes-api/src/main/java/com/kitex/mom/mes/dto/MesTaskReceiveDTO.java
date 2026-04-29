package com.kitex.mom.mes.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 生产计划接收生成生产工单
 */
@Data
public class MesTaskReceiveDTO {

    /**
     * 生产订单号
     */
    private String productionOrder;

    /**
     * 计划单号
     */
    private String planNo;

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
    private String matCode;

    /**
     * 产品名称
     */
    @Schema(description = "产品名称")
    private String matName;

    /**
     * 产品规格
     */
    @Schema(description = "产品规格")
    private String matSpec;

    /**
     * 开始时间
     */
    @Schema(description = "开始时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date planStartDate;

    /**
     * 需求日期
     */
    @Schema(description = "需求日期")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date planEndDate;

    /**
     * 需求数量
     */
    @Schema(description = "需求数量")
    private BigDecimal noticeNum;

    /**
     * 日需求量
     */
    @Schema(description = "日需求量")
    private BigDecimal dayNeedNum;

    /**
     * 计划类型
     */
    @Schema(description = "计划类型")
    private String type;

    @Schema(description = "bomId")
    private Long bomId;

    @Schema(description = "bom版本")
    private String bomVersion;

    @Schema(description = "来源类型")
    private String resourceType;

    @Schema(description = "来源id")
    private Long resourceId;

    /**
     * 工艺路线编码
     */
    @Schema(description = "工艺路线编码")
    private String processRouteCode;

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
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    private String factoryCode;
}

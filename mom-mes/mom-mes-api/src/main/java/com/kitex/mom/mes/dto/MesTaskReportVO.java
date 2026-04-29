package com.kitex.mom.mes.dto;

import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 生产报工
 *
 * @author kitex
 * @since 2025-05-13
 */
@Data
public class MesTaskReportVO {

    @Schema(description = "流转码/条码")
    private String transferCode;

    @Schema(description = "完成数量")
    private BigDecimal completeNum;

    @Schema(description = "合格数量")
    private BigDecimal passNum;

    @Schema(description = "派工单号")
    private String arrangeCode;

    @Schema(description = "任务单号")
    private String taskCode;

    @Schema(description = "工序任务单号")
    private String produceTaskCode;

    @Schema(description = "物料编码")
    private String matCode;

    @Schema(description = "物料名称")
    private String matName;

    @Schema(description = "物料规格")
    private String matSpec;

    @Schema(description = "总需求数量")
    private BigDecimal noticeNum;

    @Schema(description = "类型 手动 自动")
    private String type;

    @Schema(description = "包装规则编码")
    private String wrapRuleCode;

    @Schema(description = "箱码")
    private String wrapCode;

    @Schema(description = "容量")
    private Integer capacity;

    @Schema(description = "设备编码")
    private String deviceCode;

    @Schema(description = "设备名称")
    private String deviceName;

    @Schema(description = "工单类型")
    private String taskType;

    @Schema(description = "工作中心")
    private String worksiteCode;


    // 派工任务
    private MesTaskArrangeEntity taskArrange;

    /**
     * 生产工单
     */
    private MesTaskEntity task;

    private String factoryCode;

}

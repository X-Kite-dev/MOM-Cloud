package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;


/**
 * mes-安灯系统-安灯记录(MesAndonRecord)表实体类
 *
 * @author makejava
 * @since 2024-10-21 08:51:53
 */
@Data
public class AndonNoticeResp {

    private String id;
    /**
     * 流程编号
     */
    @Schema(description = "流程编号", name = "code")
    private String code;
    /**
     * 呼叫配置编号
     */
    @Schema(description = "呼叫配置编号", name = "configCode")
    private String configCode;
    /**
     * 异常类型编号
     */
    @Schema(description = "异常类型编号", name = "typeCode")
    private String typeCode;
    /**
     * 异常类型名称
     */
    @Schema(description = "异常类型名称", name = "typeName")
    private String typeName;
    /**
     * 车间编号
     */
    @Schema(description = "车间编号", name = "workshopCode")
    private String workshopCode;
    /**
     * 车间名称
     */
    @Schema(description = "车间名称", name = "workshopName")
    private String workshopName;
    /**
     * 产线编号
     */
    @Schema(description = "产线编号", name = "lineCode")
    private String lineCode;
    /**
     * 产线名称
     */
    @Schema(description = "产线名称", name = "lineName")
    private String lineName;
    /**
     * 工位编号
     */
    @Schema(description = "工位编号", name = "stationCode")
    private String stationCode;
    /**
     * 工位名称
     */
    @Schema(description = "工位名称", name = "stationName")
    private String stationName;
    /**
     * 班次 day白班 night夜班
     */
    @Schema(description = "班次 day白班 night夜班", name = "shift")
    private String shiftCode;
    /**
     * 班次 day白班 night夜班
     */
    @Schema(description = "班次 day白班 night夜班", name = "shift")
    private String shiftName;
    /**
     * 级别
     */
    @Schema(description = "级别", name = "level")
    private String level;
    /**
     * 故障分类编号
     */
    @Schema(description = "故障分类编号", name = "faultCode")
    private String faultCode;
    /**
     * 故障分类名称
     */
    @Schema(description = "故障分类名称", name = "faultName")
    private String faultName;
    /**
     * 故障描述
     */
    @Schema(description = "故障描述", name = "exDesc")
    private String exDesc;
    /**
     * 处理结果
     */
    @Schema(description = "处理结果", name = "result")
    private String result;
    /**
     * 流程状态 unreceive未接收，unhandle未处理，
     * up_unreceive升级未接收，up_unhandle升级未处理，handled已处理，closed已闭环，cancel已撤
     */
    @Schema(description = "流程状态 unreceive未接收，unhandle未处理， up_unreceive升级未接收，up_unhandle升级未处理，handled已处理，closed已闭环，cancel已撤")
    private String state;
    /**
     * 车间编号
     */
    @Schema(description = "车间编号", name = "deptCode")
    private String deptCode;
    /**
     * 创建者id
     */
    @Schema(description = "创建者id", name = "createBy")
    private String createBy;
    /**
     * 创建人名称
     */
    @Schema(description = "创建人名称", name = "createByName")
    private String createByName;
    /**
     * 创建时间
     */
    @Schema(description = "创建时间", name = "createTime")
    private LocalDateTime createTime;
    /**
     * 更新人id
     */
    @Schema(description = "更新人id", name = "updateBy")
    private String updateBy;
    /**
     * 更新人名称
     */
    @Schema(description = "更新人名称", name = "updateByName")
    private String updateByName;
    /**
     * 更新时间
     */
    @Schema(description = "更新时间", name = "updateTime")
    private LocalDateTime updateTime;
    /**
     * 租户ID
     */
    @Schema(description = "租户ID", name = "tenantId")
    private Integer tenantId;

    /**
     * 接收时间
     */
    @Schema(description = "接收时间", name = "receiveTime")
    private LocalDateTime receiveTime;

    /**
     * 处理时间
     */
    @Schema(description = "处理时间", name = "handleTime")
    private LocalDateTime handleTime;

    /**
     * 闭环时间
     */
    @Schema(description = "闭环时间", name = "closeTime")
    private LocalDateTime closeTime;

    /**
     * 处理人
     */
    @Schema(description = "处理人", name = "handler")
    private String handlerCode;

    /**
     * 处理人
     */
    @Schema(description = "处理人", name = "handler")
    private String handlerName;

    /**
     * 员工工号
     */
    @Schema(description = "员工工号", name = "staffCode")
    private String staffCode;
    /**
     * 员工姓名
     */
    @Schema(description = "员工姓名", name = "staffName")
    private String staffName;
    /**
     * 通知时间
     */
    @Schema(description = "通知时间", name = "noticeTime")
    private LocalDateTime noticeTime;
    /**
     * 类别 handle处理人，notice通知人
     */
    @Schema(description = "类别 handle处理人，notice通知人", name = "noticeType")
    private String noticeType;
}


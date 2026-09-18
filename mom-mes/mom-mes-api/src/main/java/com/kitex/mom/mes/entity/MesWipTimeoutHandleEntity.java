package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@TenantTable
@TableName("mes_wip_timeout_handle")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "在制超时管控记录")
public class MesWipTimeoutHandleEntity extends BaseEntity<MesWipTimeoutHandleEntity> {

    @Schema(description = "配置+库存来源+进站时间组成的事件唯一键")
    private String incidentKey;

    @Schema(description = "来源在制库存ID")
    private Long sourceStockId;

    @Schema(description = "来源报工记录ID")
    private Long sourceReportId;

    @Schema(description = "关联配置编码")
    private String configCode;

    @Schema(description = "产线编码")
    private String lineCode;

    @Schema(description = "工序编码")
    private String routeCode;

    @Schema(description = "产品型号编码")
    private String productCode;

    @Schema(description = "产品流转码/SN")
    private String transferCode;

    @Schema(description = "关联派工单号")
    private String arrangeCode;

    @Schema(description = "关联工序任务号")
    private String produceTaskCode;

    @Schema(description = "关联生产工单号")
    private String taskCode;

    @Schema(description = "车间编码")
    private String workshopCode;

    @Schema(description = "车间名称")
    private String workshopName;

    @Schema(description = "工位编码")
    private String worksiteCode;

    @Schema(description = "工位名称")
    private String worksiteName;

    @Schema(description = "进站时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime enterTime;

    @Schema(description = "本次报警时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime alertTime;

    @Schema(description = "已超时秒数")
    private Integer overtimeSec;

    @Schema(description = "当前报警等级 1/2/3")
    private Integer alertLevel;

    @Schema(description = "最近一次扫描仍发现来源在制品")
    private Boolean sourcePresent;

    @Schema(description = "最近发现来源在制品的时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastSeenAt;

    @Schema(description = "当前已通知且可处理的用户ID，逗号分隔")
    private String assignedUserCodes;

    @Schema(description = "当前已通知且可处理的用户姓名，逗号分隔")
    private String assignedUserNames;

    @Schema(description = "已通知的验证用户ID，逗号分隔")
    private String verifierUserCodes;

    @Schema(description = "已通知的验证用户姓名，逗号分隔")
    private String verifierUserNames;

    @Schema(description = "处理人账号")
    private String handlePersonCode;

    @Schema(description = "处理人姓名")
    private String handlePersonName;

    @Schema(description = "处理时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime handleTime;

    @Schema(description = "处理说明")
    private String handleDesc;

    @Schema(description = "根本原因")
    private String rootCause;

    @Schema(description = "纠正/协调措施")
    private String correctiveAction;

    @Schema(description = "处置类型 EXPEDITE/TRANSFER/QUALITY_HOLD/ENGINEERING_HOLD")
    private String disposition;

    @Schema(description = "承诺解除或重新检查时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expectedResolveTime;

    @Schema(description = "状态 0=待处理 1=待验证 2=已闭环 3=自动解除 9=已取消")
    private String status;

    @Schema(description = "验证结果 PASS/REJECT")
    private String verificationResult;

    @Schema(description = "验证说明")
    private String verificationRemark;

    @Schema(description = "验证人账号")
    private String verifierCode;

    @Schema(description = "验证人姓名")
    private String verifierName;

    @Schema(description = "验证时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime verificationTime;

    @Schema(description = "闭环/自动解除/取消时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime closeTime;

    @Schema(description = "闭环、自动解除或取消原因")
    private String closeReason;

    @Schema(description = "到期仍未解除后重新打开次数")
    private Integer reopenCount;

    @Schema(description = "L1 首次通知时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime level1NotifiedAt;

    @Schema(description = "L2 首次通知时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime level2NotifiedAt;

    @Schema(description = "L3 首次通知时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime level3NotifiedAt;

    @Schema(description = "最近通知时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastNotifyAt;

    @Schema(description = "最近通知通道（逗号分隔）")
    private String lastNotifyChannels;

    @Schema(description = "最近通知结果 SUCCESS/FAILED/PARTIAL")
    private String lastNotifyResult;

    @Schema(description = "最近通知报警等级")
    private Integer lastNotifiedLevel;

    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}

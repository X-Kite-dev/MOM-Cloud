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
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 变化点信息处理 实体类
 *
 * @author kitex
 * @since 2025-05-26
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_change_point_handle")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "变化点信息处理")
public class MesChangePointHandleEntity extends BaseEntity<MesChangePointHandleEntity> {

    @Schema(description = "变化点事件编码")
    private String eventCode;

    @Schema(description = "来源永久幂等请求号")
    private String requestId;

    /**
     * 变化点编码
     */
    @Schema(description = "变化点编码")
    private String configCode;

    /**
     * 变化点类型
     */
    @Schema(description = "变化点类型")
    private String configName;

    /**
     * 变化点内容
     */
    @Schema(description = "变化点内容")
    private String configContent;

    /**
     * 等级
     */
    @Schema(description = "等级")
    private String configLevel;

    /**
     * 上限
     */
    @Schema(description = "上限（配置快照）")
    private BigDecimal upperLimit;

    /**
     * 下限
     */
    @Schema(description = "下限（配置快照）")
    private BigDecimal lowerLimit;

    @Schema(description = "产品条码/流转码")
    private String transferCode;

    @Schema(description = "产线编码")
    private String lineCode;

    @Schema(description = "工序编码")
    private String routeCode;

    private String taskCode;
    private String produceTaskCode;
    private String arrangeCode;
    private String deviceCode;
    private String worksiteCode;

    @Schema(description = "大类（冗余，方便过滤）")
    private String category;

    @Schema(description = "产品型号编码")
    private String productCode;

    @Schema(description = "监控字段标识")
    private String monitorField;

    @Schema(description = "实际采集值（数值型变化点）")
    private BigDecimal actualValue;

    @Schema(description = "触发来源：MANUAL/IOT/MES/MDM/QMS/TPM/INTEGRATION")
    private String triggerSource;

    @Schema(description = "生产控制模式快照")
    private String controlMode;

    @Schema(description = "是否要求变化点首检")
    private Boolean inspectionRequired;

    @Schema(description = "是否要求独立验证")
    private Boolean verificationRequired;

    private Long inspectionRecordId;
    private Long inspectionId;
    private String inspectionStatus;

    private String assignedUserCodes;
    private String assignedUserNames;
    private String verifierUserCodes;
    private String verifierUserNames;
    private String lastNotifyChannels;
    private String lastNotifyResult;

    private String acceptPersonCode;
    private String acceptPersonName;
    private LocalDateTime acceptTime;

    private LocalDateTime responseDeadline;
    private LocalDateTime handleDeadline;

    private Integer escalationCount;
    private LocalDateTime lastEscalationTime;
    private LocalDateTime nextEscalationTime;

    @Schema(description = "本次报警时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime alertTime;

    @Schema(description = "本次报警等级（字典）")
    private String alertLevel;

    @Schema(description = "是否已处理 0=否 1=是")
    private String handled;

    @Schema(description = "处理人")
    private String handleBy;

    /**
     * 通知用户
     */
    @Schema(description = "通知用户")
    private String noticePersonCode;

    /**
     * 通知用户姓名
     */
    @Schema(description = "通知用户姓名")
    private String noticePersonName;

    /**
     * 触发时间
     */
    @Schema(description = "触发时间")
    private LocalDateTime triggerTime;


    /**
     * 实际情况
     */
    @Schema(description = "实际情况")
    private String actualSituation;

    /**
     * 处理角色
     */
    @Schema(description = "验证角色快照，优先取等级规则")
    private String handleRole;

    /**
     * 处理人员
     */
    @Schema(description = "处理人员")
    private String handlePersonCode;

    /**
     * 处理人员姓名
     */
    @Schema(description = "处理人员姓名")
    private String handlePersonName;

    /**
     * 解决时间
     */
    @Schema(description = "解决时间")
    private LocalDateTime handleTime;

    /**
     * 解决说明
     */
    @Schema(description = "解决说明")
    private String handleDesc;

    private String rootCause;
    private String correctiveAction;
    private String preventiveAction;
    private LocalDateTime expectedCloseTime;

    private String verificationResult;
    private String verificationRemark;
    private String verifierCode;
    private String verifierName;
    private LocalDateTime verificationTime;
    private LocalDateTime closeTime;
    private String closeReason;

    /**
     * 状态 待解决、已完成
     */
    @Schema(description = "状态 待解决、已完成")
    private String status;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}

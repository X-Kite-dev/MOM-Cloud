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

import java.time.LocalDateTime;

/**
 * 质检任务生成记录 实体类
 *
 * @author kitex
 * @since 2024-02-06
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TenantTable
@Accessors(chain = true)
@Schema(description = "质检任务生成记录")
@TableName(value = "mes_inspection_gen_record", schema = "mom_mes")
public class MesInspectionGenRecordEntity extends BaseEntity<MesInspectionGenRecordEntity> {

    /**
     * 生产工单号
     */
    @Schema(description = "生产工单号")
    private String taskCode;

    /**
     * 工序任务号
     */
    @Schema(description = "工序任务号")
    private String produceTaskCode;

    /**
     * 规则编码
     */
    @Schema(description = "规则编码")
    private String ruleCode;

    /**
     * 规则名称
     */
    @Schema(description = "规则名称")
    private String ruleName;

    /**
     * 质检任务编码
     */
    @Schema(description = "质检任务编码")
    private Long inspectionId;

    /**
     * 跨MES/QMS永久幂等请求号。
     */
    @Schema(description = "质检任务来源请求号")
    private String sourceRequestId;

    /**
     * 生成质检类型 首中末
     */
    @Schema(description = "生成质检类型 首中末")
    private String inspectionType;

    /**
     * 生成时间
     */
    @Schema(description = "生成时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime genTime;

    /**
     * 生成班次
     */
    @Schema(description = "生成班次")
    private String genShiftCode;

    /**
     * 下次生成时间
     */
    @Schema(description = "下次生成时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime nextTime;

    /**
     * 下次生成班次
     */
    @Schema(description = "下次生成班次")
    private String nextShift;

    /**
     * 是否生成成功
     */
    @Schema(description = "是否生成成功")
    private String genFlag;

    /**
     * 生成说明
     */
    @Schema(description = "生成说明")
    private String genRemark;

    /**
     * 质检结果
     */
    @Schema(description = "质检结果")
    private String inspectionResult;

    @Schema(description = "QMS质检任务状态")
    private String inspectionStatus;

    @Schema(description = "质量门状态 PENDING/PASSED/FAILED/CANCELED/ERROR")
    private String gateStatus;

    @Schema(description = "最近同步说明")
    private String syncMessage;

    @Schema(description = "因本质检任务阻断次数")
    private Integer blockedCount;

    @Schema(description = "QMS受检批总数量")
    private Integer qmsTotalNum;

    @Schema(description = "QMS累计可放行数量")
    private Integer qmsUsableNum;

    @Schema(description = "QMS累计已处置数量")
    private Integer qmsHandleNum;

    @Schema(description = "QMS累计处置结论")
    private String qmsHandleResult;

    @Schema(description = "最近同步时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastSyncTime;

    @Schema(description = "最近阻断时间")
    @JsonFormat(timezone = "GMT+8", pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastBlockedTime;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 首收件同事务锁存，阻止后续旧pull越过事件应用。可空以保持旧夹具真实NULL。
     */
    private Boolean qualityEventManaged;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Boolean qualityEventControlled;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Long qualityEventId;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Long qualityEventSequence;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String qualityEventFingerprint;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Long qualityEventVersion;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String qualityEventState;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Long qualityBlockedEventId;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String qualityEventMessage;

    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private java.util.Date qualityEventAppliedAt;
}

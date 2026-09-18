package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * <p>
 * 安灯信息处理记录 实体类
 * </p>
 *
 * @author kitex
 * @since 2025-05-18 16:44:58
 */
@Data
@TenantTable
@TableName("mes_andon_record")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "安灯信息处理记录")
public class MesAndonRecordEntity extends BaseEntity<MesAndonRecordEntity> {

    @Schema(description = "调用方幂等请求号")
    private String requestId;

    @Schema(description = "创建命令指纹，历史为空不可冒充幂等回执")
    private String requestFingerprint;

    @Schema(description = "影响生产的派工安灯须经独立复产批准后解除生产阻断")
    private Boolean productionReleaseRequired;

    /**
     * 流程编号
     */
    @Schema(description = "流程编号")
    private String code;

    /**
     * 配置编号
     */
    @Schema(description = "配置编号")
    private String configCode;

    /**
     * 异常类型编号
     */
    @Schema(description = "异常类型编号")
    private String typeCode;

    /**
     * 异常类型名称
     */
    @Schema(description = "异常类型名称")
    private String typeName;

    /**
     * 车间编号
     */
    @Schema(description = "车间编号")
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
     * 班次编码
     */
    @Schema(description = "班次编码")
    private String shiftCode;

    /**
     * 班次名称
     */
    @Schema(description = "班次名称")
    private String shiftName;

    /**
     * 级别
     */
    @Schema(description = "级别")
    private String level;

    /**
     * 故障分类编号
     */
    @Schema(description = "故障分类编号")
    private String faultCode;

    /**
     * 故障分类名称
     */
    @Schema(description = "故障分类名称")
    private String faultName;

    /**
     * 故障描述
     */
    @Schema(description = "故障描述")
    private String exDesc;

    @Schema(description = "关联生产工单号")
    private String taskCode;

    @Schema(description = "关联生产任务号")
    private String produceTaskCode;

    @Schema(description = "关联派工单号")
    private String arrangeCode;

    @Schema(description = "关联设备编码")
    private String deviceCode;

    @Schema(description = "关联工位编码")
    private String worksiteCode;

    @Schema(description = "是否影响生产")
    private Boolean impactProduction;

    @Schema(description = "是否已联动暂停生产任务")
    private Boolean productionPaused;

    @Schema(description = "生产暂停联动结果")
    private String productionPauseResult;

    /**
     * 处理结果
     */
    @Schema(description = "处理结果")
    private String result;

    @Schema(description = "根本原因")
    private String rootCause;

    @Schema(description = "纠正措施")
    private String correctiveAction;

    /**
     * 处理人工号
     */
    @Schema(description = "处理人工号")
    private String handlerCode;

    /**
     * 处理人姓名
     */
    @Schema(description = "处理人姓名")
    private String handlerName;

    /**
     * 流程状态 unreceive未接收，unhandle未处理，up_unreceive升级未接收，up_unhandle升级未处理，handled已处理，closed已闭环，cancel已撤
     */
    @Schema(description = "流程状态 unreceive未接收，unhandle未处理，up_unreceive升级未接收，up_unhandle升级未处理，handled已处理，closed已闭环，cancel已撤")
    private String state;

    @Schema(description = "接收截止时间")
    private LocalDateTime responseDeadline;

    @Schema(description = "处理截止时间")
    private LocalDateTime handleDeadline;

    @Schema(description = "当前升级层级，0为初始处理人")
    private Integer escalationStep;

    @Schema(description = "累计升级次数")
    private Integer escalationCount;

    @Schema(description = "下次升级扫描时间")
    private LocalDateTime nextEscalationTime;

    /**
     * 接收时间
     */
    @Schema(description = "接收时间")
    private LocalDateTime receiveTime;

    /**
     * 处理时间
     */
    @Schema(description = "处理时间")
    private LocalDateTime handleTime;

    /**
     * 闭环时间
     */
    @Schema(description = "闭环时间")
    private LocalDateTime closeTime;

    @Schema(description = "验证结果 PASS通过、REJECT驳回")
    private String verificationResult;

    @Schema(description = "验证说明")
    private String verificationRemark;

    @Schema(description = "验证人工号")
    private String verifierCode;

    @Schema(description = "验证人姓名")
    private String verifierName;

    @Schema(description = "验证时间")
    private LocalDateTime verificationTime;

    @Schema(description = "撤销原因")
    private String cancelReason;

    /**
     * 接收人
     */
    @Schema(description = "接收人")
    private String receiverName;

    /**
     * 接收人工号
     */
    @Schema(description = "接收人工号")
    private String receiverCode;

    /**
     * 创建者姓名
     */
    @Schema(description = "创建者姓名")
    private String createByName;

    /**
     * 所属组织
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}

package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 母件真实位置观察原账；确认不是质量放行、来源占用或转换执行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TenantTable
@TableName("mes_physical_position_survey")
@Schema(description = "母件真实位置观察原账；确认不是质量放行、来源占用或转换执行")
public class MesPhysicalPositionSurveyEntity extends BaseEntity<MesPhysicalPositionSurveyEntity> {
    /**
     * 首次采集实际工厂，永久不变。
     */
    private String factoryCode;
    /**
     * 租户内唯一永久原采集号，不能取消后重用。
     */
    private String requestId;
    /**
     * 原位置采集协议版本。
     */
    private String surveyVersion;
    /**
     * 完整采集原字符串，永久保留原字符。
     */
    private String surveySnapshot;
    /**
     * 采集版本域及原字符串SHA。
     */
    private String surveyFingerprint;
    /**
     * 服务器锁内证据版本。
     */
    private String evidenceVersion;
    /**
     * 全部原来源、工程、布局及分配证据原字符串。
     */
    private String evidenceSnapshot;
    /**
     * 服务器证据自身版本域SHA。
     */
    private String evidenceFingerprint;
    /**
     * 实际原来源规范母件码，不从客户端另取码。
     */
    private String canonicalIdentifier;
    /**
     * 候选母件对象数固定1，不是库存计量数量。
     */
    private Long physicalObjectCount;
    /**
     * 实际原来源物料编码。
     */
    private String materialCode;
    /**
     * 原派工正式工程中的完整位置布局。
     */
    private String layoutCode;
    /**
     * 原正式工程标准ID，不借当前模板补造。
     */
    private Long standardId;
    /**
     * 原工程完整正文SHA。
     */
    private String standardFingerprint;
    /**
     * 首次实际采集用户ID。
     */
    private Long actorId;
    /**
     * 首次实际实名账号。
     */
    private String actorCode;
    /**
     * OBSERVED/CONFIRMED/REJECTED/CANCELLED，不含执行终态。
     */
    private String status;
    /**
     * 实际工作流修订序；采集1，终态决策2。
     */
    private Long revision;
    /**
     * 最后明确操作ID，必须同事务存在。
     */
    private Long lastOperationId;
    /**
     * 原采集时间，永久不修改。
     */
    private LocalDateTime observedTime;
    /**
     * 终态决策时间；待确认时为空。
     */
    private LocalDateTime decidedTime;
    /**
     * 数据库记录首次事务因果号，不是身份授权。
     */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Long registrationXid;
    /**
     * 同条永久查询聚合的全部子账和事件，不作为持久字段或端侧写入内容。
     */
    @TableField(exist = false)
    private String ledgerSnapshot;
}

package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 原冻结SOP清单的执行人签收与开工核验记录。
 * 新formalSops证明正式版本和原件字节；历史旧附件仍只证明引用，不证明已理解或培训合格。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("mes_sop_execution_record")
public class MesSopExecutionRecord extends BaseEntity<MesSopExecutionRecord> {
    /**
     * 原派工身份，终端精确文本序列化。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long arrangeId;
    /**
     * 原工序任务身份。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long produceTaskId;
    /**
     * ACK人工签收或START_CHECK同事务开工核验。
     */
    private String action;
    /**
     * 原请求号，同号异正文不允许覆盖。
     */
    private String requestId;
    /**
     * 原完整签收命令指纹。
     */
    private String commandFingerprint;
    /**
     * 原冻结完整SOP清单指纹，正式版包含精确版本、原件hash及批准信息。
     */
    private String manifestFingerprint;
    /**
     * 签收时完整引用与派工范围JSON。
     */
    private String manifestJson;
    /**
     * REQUIRED/NO_REFERENCE/LEGACY_UNDECLARED。
     */
    private String manifestStatus;
    /**
     * 实名签收/开工员工身份。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorId;
    /**
     * 实名签收员工账号；派工员工集合由清单指纹冻结。
     */
    private String workerCode;
    /**
     * 人工说明或开工核验说明。
     */
    private String reason;
    /**
     * 实际登记时刻。
     */
    private LocalDateTime operationTime;
    /**
     * 实际认证工厂。
     */
    private String factoryCode;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getTenantId() {
        return super.getTenantId();
    }
}

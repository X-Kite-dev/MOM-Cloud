package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 移动端弱网报工授权账。
 *
 * <p>令牌只保存SHA-256摘要；授权与租户、工厂、人员、派工、生产标识和执行策略绑定。</p>
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_offline_report_permit")
@EqualsAndHashCode(callSuper = true)
public class MesOfflineReportPermitEntity extends BaseEntity<MesOfflineReportPermitEntity> {
    private String permitNo;
    private String tokenHash;
    private String factoryCode;
    private Long operatorId;
    private String operatorCode;
    private String arrangeCode;
    private String taskCode;
    private String produceTaskCode;
    private String submittedIdentifier;
    private String reportMode;
    private String allowedWorksiteCodes;
    private String contractFingerprint;
    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;
    private Integer maxUseCount;
    private Integer usedCount;
    private String status;
    private LocalDateTime lastUsedAt;
}

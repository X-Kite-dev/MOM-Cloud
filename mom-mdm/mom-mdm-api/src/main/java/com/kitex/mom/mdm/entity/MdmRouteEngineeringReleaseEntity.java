package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 工程联合发布版本，封存工程正文不可覆盖。
 */
@Data
@TenantTable
@TableName("mdm_route_engineering_release")
@EqualsAndHashCode(callSuper = true)
public class MdmRouteEngineeringReleaseEntity extends BaseEntity<MdmRouteEngineeringReleaseEntity> {
    /**
     * 实际授权工厂编码。
     */
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
    /**
     * PRODUCTION为完整生产工程版，REWORK_OVERRIDE为单节点返工覆盖版。
     */
    private String releasePurpose;
    /**
     * 返工覆盖绑定的原生产工程版本；生产版为空。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long baseReleaseId;
    /**
     * 原生产工程版本冻结正文SHA256。
     */
    private String baseContentHash;
    /**
     * 返工覆盖的唯一稳定节点；生产版为空。
     */
    private String targetRouteNodeCode;
    /**
     * 规范化覆盖类别JSON数组，只允许SOP/PARAMETER。
     */
    private String overrideCategoriesSnapshot;
    /**
     * 真实路线ID。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long routeId;
    /**
     * 路线稳定编码。
     */
    private String routeCode;
    /**
     * 路线产品编码。
     */
    private String productCode;
    /**
     * 精确正式BOM版本ID。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long bomId;
    /**
     * BOM业务版本。
     */
    private String bomVersion;
    /**
     * 封存时真实图版本。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long graphRevision;
    /**
     * 路线内递增工程版本号。
     */
    private Integer versionNo;
    /**
     * PENDING/RELEASED/REJECTED/RETIRED/WITHDRAWN。
     */
    private String status;
    /**
     * 服务器组装的原始UTF-8工程正文，不可覆盖。
     */
    private String bundleSnapshot;
    /**
     * 原始工程正文SHA256。
     */
    private String contentHash;
    /**
     * 六类逐节点适用声明原文。
     */
    private String applicabilitySnapshot;
    /**
     * 原提交永久请求号。
     */
    private String submitRequestId;
    /**
     * 最近成功生命周期命令永久号，与同事务命令账绑定。
     */
    private String lastRequestId;
    /**
     * 实名提交用户ID。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long submittedUserId;
    /**
     * 提交用户名称。
     */
    private String submittedBy;
    /**
     * 提交时间。
     */
    private LocalDateTime submittedTime;
    /**
     * 最近批准或退回实名用户ID。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long reviewedUserId;
    /**
     * 最近批准或退回用户名称。
     */
    private String reviewedBy;
    /**
     * 最近批准或退回时间。
     */
    private LocalDateTime reviewedTime;
    /**
     * 最近生命周期命令依据。
     */
    private String lastReason;
    /**
     * 生命周期并发版本，创建为0，每次合法迁移递增。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long lifecycleRevision;

    /**
     * 主键以文本出网，避免前端精度损失。
     */
    @Override
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public Long getId() {
        return super.getId();
    }

    /**
     * 实际租户以文本出网。
     */
    @Override
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public Long getTenantId() {
        return super.getTenantId();
    }
}

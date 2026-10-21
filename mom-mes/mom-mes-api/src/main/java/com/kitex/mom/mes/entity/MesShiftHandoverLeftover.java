package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 班次遗留事项原责任账。跨班引用同一ID，不克隆多份独立状态。
 */
@Data
@TenantTable
@TableName("mes_shift_handover_leftover")
@EqualsAndHashCode(callSuper = true)
public class MesShiftHandoverLeftover extends BaseEntity<MesShiftHandoverLeftover> {
    /**
     * 当前实际授权工厂。
     */
    private String factoryCode;
    /**
     * 首次创建该事项的正式交接版本。后续结转通过link账引用同一事项。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long handoverId;
    /**
     * 本次交班中由前端生成的稳定草稿标识，防止重复新增。
     */
    private String clientItemKey;
    /**
     * PRODUCTION / QUALITY / EQUIPMENT / MATERIAL / SAFETY。
     */
    private String category;
    /**
     * 原事项标题，不因跨班结转改写。
     */
    private String title;
    /**
     * 原问题与核验要求。
     */
    private String description;
    /**
     * MES_DISPATCH对应精确派工号；人工声明不得夹带虚构派工。
     */
    private String arrangeCode;
    /**
     * MES_DISPATCH取权威派工状态；HUMAN_DECLARED是实名现场说明，不伪称自动取证。
     */
    private String sourceType;
    /**
     * 当前责任人的真实登录账号ID，不是员工档案编号。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long ownerUserId;
    /**
     * 每次指派时的真实账号及工厂权限安全投影。
     */
    private String ownerSnapshotJson;
    /**
     * 明确处理期限，不能早于原交班结束。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime dueAt;
    /**
     * OPEN → DONE_PENDING → VERIFIED；REOPEN保留所有历史操作。
     */
    private String status;
    /**
     * 每次办理加一，expectedRevision用于阻止并发覆盖。
     */
    private Integer revision;
    /**
     * 原编制账号，不能独立验证自己编制的事项。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long submittedBy;
    /**
     * 当前轮次报请验证的账号；重开/改派时清空，旧值保留操作账。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long completedBy;
    /**
     * 完成依据，明确人工声明或原派工完成事实，不能用文本冒充系统事实。
     */
    private String resolutionEvidenceJson;
    /**
     * 当前完成依据原文SHA-256。
     */
    private String resolutionFingerprint;
    /**
     * 编制人和完成人之外的独立核验账号。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long verifiedBy;
    /**
     * 本轮独立核验时间。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime verifiedAt;

    @Override
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public Long getId() {
        return super.getId();
    }

    @Override
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    public Long getTenantId() {
        return super.getTenantId();
    }
}

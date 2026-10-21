package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 正式交接版本。原V1/V2数量证据与V3班次/人员证据独立冻结，历史不可覆盖。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TenantTable
@TableName("mes_shift_handover")
public class MesShiftHandover extends BaseEntity<MesShiftHandover> {
    /**
     * 实际授权工厂。
     */
    private String factoryCode;
    /**
     * 已结束时间窗和精确派工集合的摘要，用于同范围版本链。
     */
    private String scopeKey;
    /**
     * 业务显示名称，不用名称推断班次主数据。
     */
    private String shiftLabel;
    /**
     * 交班开始时刻，包含该边界。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime windowStart;
    /**
     * 交班结束时刻，不包含该边界；跨午夜由正式班次计算。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime windowEnd;
    /**
     * 本次明确选择并排序的派工编码集合。
     */
    private String arrangeCodesJson;
    /**
     * 同一范围的正式版本号。
     */
    private Integer revision;
    /**
     * 同范围已处理的上一版本；不原地覆盖。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long previousId;
    /**
     * PENDING / ACCEPTED / REJECTED / WITHDRAWN。
     */
    private String status;
    /**
     * MES_SHIFT_BOOKED_V1/V2的原始已入账数量/工时证据。
     */
    private String snapshotJson;
    /**
     * 账面证据原文SHA-256。
     */
    private String sourceFingerprint;
    /**
     * 原交班依据。
     */
    private String reason;
    /**
     * 交班人真实账号。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long applicantId;
    /**
     * 接收、驳回或撤回的实际账号，撤回只能由原交班人执行。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long receiverId;
    /**
     * 本次实际接班/退回/撤回依据。
     */
    private String receiveReason;
    /**
     * 正式交接V3的班次、指定接班负责人、现场声明及当时在制观察；不改写原账面V1/V2。
     */
    private String formalSnapshotJson;
    /**
     * 正式元数据SHA256；与已结束时间窗的sourceFingerprint分别保存。
     */
    private String formalFingerprint;
    /**
     * 只有指定负责人可代表接班班组接受/退回；每次操作仍核当前账号及工厂授权。
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long designatedReceiverId;

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

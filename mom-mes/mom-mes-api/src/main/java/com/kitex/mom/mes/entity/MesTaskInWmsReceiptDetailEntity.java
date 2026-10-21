package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 成品入库数量到原始末工序报工的不可变分配明细。
 *
 * <p>该明细是后续成品红冲、报工冲销和订单交付追溯的数量来源，不允许用工单汇总量
 * 猜测具体报工。历史无法唯一归属的入库不会补造本明细。</p>
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("mes_task_in_wms_receipt_detail")
@EqualsAndHashCode(callSuper = true)
public class MesTaskInWmsReceiptDetailEntity extends BaseEntity<MesTaskInWmsReceiptDetailEntity> {

    /**
     * 成品入库命令回执主键。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long receiptId;
    /**
     * 被本次入库消耗可入库数量的原始报工主键。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long reportId;
    /**
     * 原始报工的永久业务请求号，用于跨表核对而不是替代报工主键。
     */
    private String reportRequestId;
    /**
     * 来源生产工单编码。
     */
    private String taskCode;
    /**
     * 来源末工序派工单编码。
     */
    private String arrangeCode;
    /**
     * 来源末工序任务编码。
     */
    private String produceTaskCode;
    /**
     * 本次入库从该报工质量可用量中分配的数量。
     */
    private BigDecimal allocatedQuantity;
    /**
     * 分配时的质量数量状态，不代表之后QMS结论不会变化。
     */
    private String qualityQuantityStatus;
    /**
     * 允许本笔报工数量进入成品入库的QMS任务ID JSON数组。
     */
    private String qualityTaskIds;
    /**
     * 入库时冻结的QMS质量证据SHA-256指纹。
     */
    private String qualityEvidenceFingerprint;
    /**
     * 入库时冻结的QMS质量证据快照。
     */
    private String qualityEvidenceSnapshot;
    /**
     * 原始报工发生时间，用于稳定FIFO和审计展示。
     */
    private LocalDateTime reportTime;
    /**
     * 分配时原报工核心事实的SHA-256指纹。
     */
    private String sourceFingerprint;
    /**
     * 实际工厂编码，由服务端上下文和来源工单确定。
     */
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }
}

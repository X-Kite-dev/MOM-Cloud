package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 生产物料耗用永久主账。与合格报告主键域分离，不作为库存、PASS或转换许可。
 * 当前协议仅接首次新不良事务的实际投料耗用；旧声明不得追溯补建。
 */
@Data
@TenantTable
@TableName("mes_production_material_operation")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "生产物料耗用原事务")
public class MesProductionMaterialOperationEntity extends BaseEntity<MesProductionMaterialOperationEntity> {
    /**
     * 原不良上报请求号；重复只返回原账，不再耗料。
     */
    private String requestId;
    /**
     * 实际NG来源，不得填写合格报告ID。
     */
    private Long sourceUnqualifiedReportId;
    /**
     * 原实际工单、工序任务及派工归属。
     */
    private String taskCode;
    private String produceTaskCode;
    private String arrangeCode;
    /**
     * 原工序编码，需求只解析该工序。
     */
    private String routeCode;
    /**
     * 本次已冻结的不良数量及基本单位，不是合格完成量。
     */
    private BigDecimal processedQuantity;
    private String productionUnit;
    /**
     * FEED；其他耗用协议须独立实现，不自动回落。
     */
    private String accountingMode;
    /**
     * MATERIAL_RECORDED仅表示投料耗用已记账。
     */
    private String status;
    /**
     * 实际登录人ID及原NG来源SHA。
     */
    private Long actorId;
    private String sourceFingerprint;
    /**
     * 当次真实工单原快照；不可按当前主数据重解释。
     */
    private String routeSnapshot;
    private String bomSnapshot;
    /**
     * 原固定协议、完整正文及版本域SHA。
     */
    private String commandVersion;
    private String commandSnapshot;
    private String commandFingerprint;
    /**
     * 实际工厂，客户端不能指定执行归属。
     */
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}

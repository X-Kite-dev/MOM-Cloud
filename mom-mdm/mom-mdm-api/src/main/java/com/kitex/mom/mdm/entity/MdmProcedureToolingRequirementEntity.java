package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 工艺路线某次节点的有限工装资源要求，主工序编码保留能力语义。 */
@Data
@TenantTable
@TableName("mdm_procedure_tooling_requirement")
@EqualsAndHashCode(callSuper = true)
public class MdmProcedureToolingRequirementEntity extends BaseEntity<MdmProcedureToolingRequirementEntity> {
    /** 工艺路线编码。 */
    private String routeCode;

    /** 独立路线节点身份，由服务端核对真实归属。 */
    @Schema(description = "稳定路线节点编号，不等同主工序编码")
    private String routeNodeCode;

    /** 主工序编码/名称，不作为各次节点配置的唯一键。 */
    private String processCode;
    private String processName;

    /** 工装分类：模具、刀具、夹具、量具或辅具。 */
    private String toolingType;

    /** 当前节点内的需求序号，不同需求分别占用有限资源。 */
    private Integer requirementSeq;

    /** 同时需要的工装数量。 */
    private Integer requiredQuantity;

    /** 寿命扣减口径：固定、按生产数量或按加工小时。 */
    @Schema(description = "FIXED/PER_QUANTITY/PER_HOUR")
    private String usageMode;

    /** 所选口径下的单位寿命消耗。 */
    private BigDecimal usageValue;

    /** 限定候选工装；空集合表示同类型启用工装。 */
    private String[] candidateToolingCodes;

    /** 是否检查工装与实际设备兼容性。 */
    private Boolean deviceCompatibilityRequired;

    /** 是否为必须满足的工装需求。 */
    private Boolean mandatory;

    /** 当前要求是否启用。 */
    private Boolean status;

    /** 工厂归属，保存时来自实际路线节点。 */
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}

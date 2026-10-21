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

/**
 * 工艺参数标准版本中的参数明细。
 *
 * <p>每条明细把一个路线工序与一个IoT属性标识符绑定，并定义形成合规结论所需的
 * 数值边界和证据充分性要求。所属标准发布后，明细不可新增、修改或删除。</p>
 */
@Data
@TenantTable
@TableName("mdm_process_parameter_item")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工艺参数标准明细")
public class MdmProcessParameterItemEntity extends BaseEntity<MdmProcessParameterItemEntity> {

    @Schema(description = "所属工艺参数标准版本ID")
    private Long standardId;

    @Schema(description = "标准编码快照")
    private String standardNo;

    @Schema(description = "标准版本号快照")
    private Integer standardVersionNo;

    @Schema(description = "工艺路线编码快照")
    private String routeCode;

    @Schema(description = "精确路线节点；历史V1明细为空，发布原文不得补写")
    private String routeNodeCode;

    @Schema(description = "受控工序编码")
    private String processCode;

    @Schema(description = "受控工序名称快照")
    private String processName;

    @Schema(description = "与IoT物模型属性一致的唯一标识符")
    private String identifier;

    @Schema(description = "工艺参数名称")
    private String parameterName;

    @Schema(description = "数值类型：DECIMAL或INTEGER")
    private String dataType;

    @Schema(description = "计量单位；应与IoT属性单位一致")
    private String unit;

    @Schema(description = "目标值")
    private BigDecimal targetValue;

    @Schema(description = "控制下限，允许为空")
    private BigDecimal lowerLimit;

    @Schema(description = "控制上限，允许为空")
    private BigDecimal upperLimit;

    @Schema(description = "预警下限，必须位于控制范围内")
    private BigDecimal warningLowerLimit;

    @Schema(description = "预警上限，必须位于控制范围内")
    private BigDecimal warningUpperLimit;

    @Schema(description = "是否为工单执行必采参数")
    private Boolean required;

    @Schema(description = "RANGE按最小/最大判定，AVERAGE按加权平均判定")
    private String aggregationMode;

    @Schema(description = "形成合规结论所需的最少GOOD数值采样数")
    private Integer minimumSampleCount;

    @Schema(description = "允许的非GOOD采样占比，0到100")
    private BigDecimal maximumNonGoodRatePct;

    @Schema(description = "连续越界秒数阈值，为后续时序异常检测保留")
    private Integer continuousDeviationSeconds;

    @Schema(description = "参数判定补充说明")
    private String remark;

    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}

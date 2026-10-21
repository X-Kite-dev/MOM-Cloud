package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * APS 排产产品族主数据。
 *
 * <p>产品族用于描述具有相近换型特征的一组产品，是顺序相关换型矩阵的匹配维度。
 * 它与库存分类、财务物料组相互独立；同一租户、工厂内产品族编码唯一。</p>
 */
@Data
@TenantTable
@TableName("mdm_scheduling_family")
@EqualsAndHashCode(callSuper = true)
@Schema(name = "MdmSchedulingFamily", description = "APS排产产品族主数据，用于换型矩阵和相邻产品排序")
public class MdmSchedulingFamilyEntity extends BaseEntity<MdmSchedulingFamilyEntity> {

    /**
     * 产品族编码，同一租户、工厂内唯一；创建后不允许直接修改。
     */
    @Schema(description = "产品族编码；同一租户、工厂内唯一，创建后不可修改", example = "FAMILY-A")
    private String familyCode;

    /**
     * 产品族名称。
     */
    @Schema(description = "产品族名称", example = "A系列壳体")
    private String familyName;

    /**
     * 产品族划分依据及适用范围说明。
     */
    @Schema(description = "产品族说明，描述换型特征、划分依据和适用范围")
    private String description;

    /**
     * 列表显示顺序，数值越小越靠前。
     */
    @Schema(description = "显示顺序，数值越小越靠前", example = "100")
    private Integer sortOrder;

    /**
     * 是否启用。仍有启用物料归属时不允许停用。
     */
    @Schema(description = "启用状态；true=启用，false=停用", example = "true")
    private Boolean enabled;

    /**
     * 数据所属工厂编码，由当前工厂上下文在新增时自动填充。
     */
    @Schema(description = "所属工厂编码，由当前工厂上下文自动填充", accessMode = Schema.AccessMode.READ_ONLY)
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}

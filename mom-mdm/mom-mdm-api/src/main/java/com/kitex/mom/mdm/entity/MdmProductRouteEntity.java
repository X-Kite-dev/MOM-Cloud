package com.kitex.mom.mdm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 产品和工艺路线关联
 *
 * @author kitex
 * @date 2024-10-30 15:21:23
 */
@Data
@TenantTable
@TableName("mdm_product_route")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "产品和工艺路线关联")
public class MdmProductRouteEntity extends BaseEntity<MdmProductRouteEntity> {

    /**
     * 产品编码
     */
    @Schema(description = "产品编码")
    private String productCode;

    /**
     * 产品名称
     */
    @Schema(description = "产品名称")
    private String productName;

    /**
     * 工艺路线编码
     */
    @Schema(description = "工艺路线编码")
    private String routeCode;

    /**
     * 工艺路线名称
     */
    @Schema(description = "工艺路线名称")
    private String routeName;

    /**
     * 状态
     */
    @Schema(description = "状态")
    private Boolean status;

    /**
     * 优先级
     */
    @Schema(description = "优先级")
    private Integer level;

    /**
     * 所属车间
     */
    @Schema(description = "所属车间")
    private Long processingWorkshop;

}
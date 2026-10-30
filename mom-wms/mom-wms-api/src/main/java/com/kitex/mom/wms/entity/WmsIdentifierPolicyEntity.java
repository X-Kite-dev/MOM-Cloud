package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 按入库业务类型和物料配置批次、条码及单件身份校验。
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("wms_identifier_policy")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "WMS批次与条码规则")
public class WmsIdentifierPolicyEntity extends BaseEntity<WmsIdentifierPolicyEntity> {
    @Schema(description = "入库业务类型；*表示全部")
    private String billType;

    @Schema(description = "物料编码；*表示全部")
    private String materialCode;

    @Schema(description = "批次是否必填")
    private Boolean batchRequired;

    @Schema(description = "批次完整匹配正则，空表示不限制格式")
    private String batchPattern;

    @Schema(description = "入库明细缺少批次时按物料模板生成")
    private Boolean autoBatchOnMissing;

    @Schema(description = "条码或序列号是否必填")
    private Boolean barcodeRequired;

    @Schema(description = "条码完整匹配正则，空表示不限制格式")
    private String barcodePattern;

    @Schema(description = "单件入库明细缺少条码时按物料模板生成")
    private Boolean autoSerialOnMissing;

    @Schema(description = "是否单件管理；要求条码必填且入库明细数量为1")
    private Boolean serialManaged;

    @Schema(description = "启用状态")
    private Boolean status;

    @Schema(description = "规则说明")
    private String remark;

    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;
}

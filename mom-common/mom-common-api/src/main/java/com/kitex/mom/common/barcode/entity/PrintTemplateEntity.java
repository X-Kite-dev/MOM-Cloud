package com.kitex.mom.common.barcode.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gitee.sunchenbin.mybatis.actable.annotation.Column;
import com.gitee.sunchenbin.mybatis.actable.annotation.Table;
import com.gitee.sunchenbin.mybatis.actable.constants.MySqlTypeConstant;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 打印模板
 */
@Data
@TenantTable
@TableName("print_template")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "打印模板")
@Table(comment = "打印模板")
public class PrintTemplateEntity extends BaseEntity<PrintTemplateEntity> {

    /**
     * 业务类型编码
     */
    @Schema(description = "业务类型编码")
    private String businessTypeCode;

    /**
     * 业务类型名称
     */
    @Schema(description = "业务类型名称")
    private String businessTypeName;

    /**
     * 模板编码
     */
    @Column(comment = "模板编码")
    @Schema(description = "模板编码")
    private String code;

    /**
     * 模板内容
     */
    @Column(comment = "模板内容", type = MySqlTypeConstant.TEXT)
    @Schema(description = "模板内容")
    private String content;

    /**
     * 模板名称
     */
    @Column(comment = "模板名称")
    @Schema(description = "模板名称")
    private String name;

    /**
     * 数据结构
     */
    @Column(comment = "数据结构", type = MySqlTypeConstant.TEXT)
    @Schema(description = "数据结构")
    private String jsonData;

    /**
     * 状态
     */
    @Column(comment = "状态")
    @Schema(description = "状态")
    private Boolean status;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}

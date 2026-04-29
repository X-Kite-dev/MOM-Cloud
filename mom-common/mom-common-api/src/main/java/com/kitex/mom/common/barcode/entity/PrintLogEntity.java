package com.kitex.mom.common.barcode.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gitee.sunchenbin.mybatis.actable.annotation.Column;
import com.gitee.sunchenbin.mybatis.actable.annotation.IsNotNull;
import com.gitee.sunchenbin.mybatis.actable.annotation.Table;
import com.gitee.sunchenbin.mybatis.actable.constants.MySqlTypeConstant;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TenantTable
@TableName("print_log")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "打印记录")
@Table(comment = "打印记录")
public class PrintLogEntity extends BaseEntity<PrintLogEntity> {

    @Column(comment = "打印设备编码")
    @IsNotNull
    @Schema(description = "打印设备编码")
    private String deviceCode;

    @Column(comment = "打印设备名称")
    @IsNotNull
    @Schema(description = "打印设备名称")
    private String deviceName;

    @Column(comment = "终端IP")
    @IsNotNull
    @Schema(description = "终端IP")
    private String address;

    @Column(comment = "模板编码")
    @IsNotNull
    @Schema(description = "模板编码")
    private String templateCode;

    @Column(comment = "模板内容", type = MySqlTypeConstant.TEXT)
    @IsNotNull
    @Schema(description = "模板内容")
    private String templateContent;

    @Column(comment = "打印内容", type = MySqlTypeConstant.TEXT)
    @IsNotNull
    @Schema(description = "打印内容")
    private String jsonData;

    @Column(comment = "打印结果")
    @IsNotNull
    @Schema(description = "打印结果")
    private Boolean result;

    /**
     * 工厂编码
     */
    @Schema(description = "工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}

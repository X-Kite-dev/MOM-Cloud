package com.kitex.mom.mdm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 生产采集实际物料基本计量观察，不是工程发布或跨域执行许可。
 * ID和原更新时间使用文本，保留数据库精度，不经公共秒级日期序列化。
 */
@Data
@Schema(description = "实际物料基本计量观察")
public class MdmMaterialMeasureDTO {

    /**
     * 实际物料主键，不能用编码替代版本身份。
     */
    @Schema(description = "实际物料ID文本")
    private String materialId;

    /**
     * 原读取时实际租户。
     */
    @Schema(description = "实际租户ID文本")
    private String tenantId;

    /**
     * 原读取时实际工厂。
     */
    @Schema(description = "实际工厂编码")
    private String factoryCode;

    /**
     * 实际物料编码。
     */
    @Schema(description = "实际物料编码")
    private String materialCode;

    /**
     * 原基本单位，不能默认件或由调用方指定。
     */
    @Schema(description = "实际基本单位编码")
    private String basicUnit;

    /**
     * 原读取时真实启用状态，不能用非空字符串当true。
     */
    @Schema(description = "实际启用状态")
    private Boolean enabled;

    /**
     * 原数据库更新时间ISO本地时间文本含微秒；原NULL保持NULL。
     */
    @Schema(description = "原精确更新时间文本")
    private String updatedAt;
}

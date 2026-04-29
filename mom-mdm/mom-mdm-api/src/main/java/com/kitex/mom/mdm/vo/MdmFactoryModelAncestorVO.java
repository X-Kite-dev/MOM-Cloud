package com.kitex.mom.mdm.vo;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 工厂建模树祖宗VO
 *
 * @author kitex
 * @date 2025-08-29 11:25:17
 */
@Data
@Schema(description = "工厂建模树祖宗VO")
public class MdmFactoryModelAncestorVO {

    /**
     * 工位ID（工厂建模节点ID）
     */
    @Schema(description = "工位ID（工厂建模节点ID）")
    private Long stationId;

    /**
     * 工位编码
     */
    @Schema(description = "工位编码")
    private String stationCode;

    /**
     * 工位名称
     */
    @Schema(description = "工位名称")
    private String stationName;

    /**
     * 线体ID（工厂建模节点ID）
     */
    @Schema(description = "线体ID（工厂建模节点ID）")
    private Long lineId;

    /**
     * 线体编码
     */
    @Schema(description = "线体编码")
    private String lineCode;

    /**
     * 线体名称
     */
    @Schema(description = "线体名称")
    private String lineName;

    /**
     * 车间ID（工厂建模节点ID）
     */
    @Schema(description = "车间ID（工厂建模节点ID）")
    private Long workshopId;

    /**
     * 车间编码
     */
    @Schema(description = "车间编码")
    private String workshopCode;

    /**
     * 车间名称
     */
    @Schema(description = "车间名称")
    private String workshopName;

    /**
     * 工厂ID（工厂建模节点ID）
     */
    @Schema(description = "工厂ID（工厂建模节点ID）")
    private Long factoryId;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 工厂名称
     */
    @Schema(description = "工厂名称")
    private String factoryName;
}

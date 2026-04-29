package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.mom.qms.enums.InspectionHistoryTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 成品质检历史
 *
 * @author kitex
 * @date 2022/12/9
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Accessors(chain = true)
@Schema(description = "成品质检历史记录")
@TableName(value = "qms_inspection_product_history", autoResultMap = true)
public class QmsInspectionProductHistoryEntity extends BaseEntity<QmsInspectionProductHistoryEntity> {

    @Schema(description = "关联任务Id")
    private Long taskId;

    /**
     * @see InspectionHistoryTypeEnum
     */
    @Schema(description = "记录的类型")
    private String type;

    @Schema(description = "数量(分别是新建/追加/已检测的数量)")
    private Integer num;

    @Schema(description = "处理的质检项")
    private String detailName;

    @Schema(description = "发生的时间")
    private Date time;

    @Schema(description = "合格状态")
    private Boolean qualifiedStatus;

    @Schema(description = "作废原因")
    private String cancelReason;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

}

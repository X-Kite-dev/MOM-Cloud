package com.kitex.mom.qms.dto.inspection;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 检测项检测结果记录
 *
 * @author kitex
 * @date 2022/12/1
 */
@Data
@Accessors(chain = true)
@Schema(description = "检测项检测结果记录")
public class InspectionItemResultRecord {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键id")
    private Long id;

    @Schema(description = "任务id")
    private Long taskId;

    @Schema(description = "质检项id")
    private Long detailId;

    @Schema(description = "质检项名称")
    private String detailName;

    @Schema(description = "质检时间")
    private Date time;

    @Schema(description = "数值 点检类型为上下限or范围时必填")
    private Double doubleValue;

    @Schema(description = "是否合格, 点检类型Bool时必填")
    private Boolean qualified;

}

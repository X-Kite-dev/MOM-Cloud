package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 批量出库明细
 *
 * @author kitex
 * @date 2025-04-09 16:08:02
 */
@Data
@TableName("wms_out_notice_batch_detail")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "批量出库明细")
public class WmsOutNoticeBatchDetailEntity extends Model<WmsOutNoticeBatchDetailEntity> {


    /**
     * 主键id
     */
    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键id")
    private Long id;

    /**
     * 单据编码
     */
    @Schema(description = "单据编码")
    private String billCode;

    /**
     * 通知单id
     */
    @Schema(description = "通知单id")
    private Long noticeId;
}
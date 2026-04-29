package com.kitex.mom.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 批量出库表
 *
 * @author kitex
 * @date 2025-04-09 16:04:44
 */
@Data
@TenantTable
@TableName("wms_out_notice_batch")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "批量出库表")
public class WmsOutNoticeBatchEntity extends BaseEntity<WmsOutNoticeBatchEntity> {


    /**
     * 单据编码
     */
    @Schema(description = "单据编码")
    private String billCode;

    /**
     * 状态 0 待审核  1 审核通过  2 审核驳回
     */
    @Schema(description = "状态 0 待审核  1 审核通过  2 审核驳回")
    private String state;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /**
     * 创建人
     */
    @Schema(description = "创建人")
    private String createByName;

    /**
     * 关联的通知单
     */
    @TableField(exist = false)
    private List<WmsOutNoticeEntity> noticeList;

    /**
     * 关联的通知单明细汇总
     */
    @TableField(exist = false)
    private List<WmsOutNoticeDetailEntity> noticeDetailList;
}
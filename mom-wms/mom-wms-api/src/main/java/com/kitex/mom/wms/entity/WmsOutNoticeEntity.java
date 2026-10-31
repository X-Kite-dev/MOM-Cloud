package com.kitex.mom.wms.entity;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.github.yulichang.annotation.EntityMapping;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 出库通知单
 *
 * @author kitex
 * @date 2024-12-23 20:07:39
 */
@Data
@TenantTable
@TableName("wms_out_notice")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "出库通知单")
public class WmsOutNoticeEntity extends BaseEntity<WmsOutNoticeEntity> {

    /**
     * 出库通知单状态
     * 0待处理、1处理中、2已完成、3已作废
     */
    @Schema(description = "出库通知单状态")
    private String state;

    /**
     * 出库完成时间
     */
    @Schema(description = "出库完成时间")
    private LocalDateTime completeTime;

    /**
     * 出库仓编码
     */
    @Schema(description = "出库仓编码")
    private String warehouseCode;

    /**
     * 出库仓名称
     */
    @Schema(description = "出库仓名称")
    private String warehouseName;

    /**
     * 通知出库总数
     */
    @Schema(description = "通知出库总数")
    private Double noticeOutNum;

    /**
     * 出库总数
     */
    @Schema(description = "出库总数")
    private Double allOutNum;

    /**
     * 未出库总数
     */
    @Schema(description = "未出库总数")
    private Double allNotOutNum;

    /**
     * 车间名称
     */
    @Schema(description = "车间名称")
    private String workshop;

    /**
     * 通知单类型
     */
    @Schema(description = "通知单类型")
    private String billType;

    /**
     * 业务单据id
     */
    @Schema(description = "业务单据id")
    private Long documentId;

    /**
     * 业务单据表名
     */
    @Schema(description = "业务单据表名")
    private String documentTable;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

    /**
     * 扩展字段
     */
    @Schema(description = "扩展字段")
    private String expansion;

    /**
     * 工厂编码 - 数据所属的工厂编码
     */
    @Schema(description = "工厂编码 - 数据所属的工厂编码")
    @TableField(fill = FieldFill.INSERT)
    private String factoryCode;

    /** MES来源受理修订号；旧来源按第1次修订处理。 */
    private Integer sourceAcceptanceRevision;

    @ExcelIgnore
    @TableField(exist = false)
    @EntityMapping(thisField = "id", joinField = "noticeId")
    private List<WmsOutNoticeDetailEntity> detailList;

}

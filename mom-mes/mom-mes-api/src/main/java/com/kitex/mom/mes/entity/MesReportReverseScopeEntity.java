package com.kitex.mom.mes.entity;

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
 * 报工冲销申请冻结的逐报工影响范围。
 *
 * <p>单笔冲销也保存一行。标识链式冲销按下游到上游保存多行，活动标志用于保证
 * 同一报工同一时刻只能进入一张待复核申请；终态只关闭活动标志，不删除冻结证据。</p>
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_report_reverse_scope")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "报工冲销冻结范围")
public class MesReportReverseScopeEntity extends BaseEntity<MesReportReverseScopeEntity> {

    @Schema(description = "冲销申请ID")
    private Long reverseRequestId;
    @Schema(description = "原报工ID")
    private Long reportId;
    @Schema(description = "执行顺序；1表示最先撤回的最下游报工")
    private Integer sequenceNo;
    @Schema(description = "工序编码")
    private String routeCode;
    @Schema(description = "工序名称")
    private String routeName;
    @Schema(description = "工序顺序")
    private Integer routeOrder;
    @Schema(description = "该报工申请时事实指纹")
    private String evidenceFingerprint;
    @Schema(description = "执行该报工反向事务使用的永久请求号")
    private String childReverseRequestId;
    @Schema(description = "是否仍占用报工的待复核资格")
    private Boolean active;
    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "实际工厂编码")
    private String factoryCode;
}

package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.util.TenantTable;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/** 检验依据永久原件；不继承可更新、逻辑删除的业务基类，不向外提供CRUD。 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName(value = "qms_inspection_basis", schema = "mom_qms")
public class QmsInspectionBasisEntity {
    @TableId private Long id;
    private Long tenantId;
    private String factoryCode;
    private String inspectionType;
    private Long taskId;
    private Integer revision;
    private String operation;
    private String previousFingerprint;
    private String payload;
    private String payloadFingerprint;
    private Long createdBy;
    private Date occurredAt;

    /** 仅查询时使用的有界链元数据，不是业务表列。 */
    @TableField(exist = false)
    private Long lineageRows;

    @TableField(exist = false)
    private Long lineageBytes;
}

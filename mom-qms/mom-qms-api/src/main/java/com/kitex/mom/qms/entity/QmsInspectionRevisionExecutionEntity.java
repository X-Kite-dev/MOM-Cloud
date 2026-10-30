package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/** 受控修订协调的独立canonical账，不继承可软删除业务实体。 */
@Data
@Accessors(chain = true)
@TableName(value = "mom_qms.qms_inspection_revision_execution", autoResultMap = true)
public class QmsInspectionRevisionExecutionEntity {
    @TableId private Long id;
    private Long tenantId;
    private String factoryCode;
    private Long taskId;
    private String intent;
    private String intentFingerprint;
    private Long executorId;
    private Date createdAt;
}

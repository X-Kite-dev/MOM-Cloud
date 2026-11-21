package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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
    /** 每次追加依据时由应用分配的行主键。依据表没有自增列或数据库默认值。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 原检验任务所属租户。 */
    private Long tenantId;

    /** 原检验任务所属工厂编码。 */
    private String factoryCode;

    /** 检验任务类型，PRODUCT 为成品，PROCEDURE 为工序。 */
    private String inspectionType;

    /** 当前组织及检验类型下的原任务主键。 */
    private Long taskId;

    /** 本任务依据的版本序号，从 1 开始递增。 */
    private Integer revision;

    /** 形成版本的操作，CREATE、RECONFIGURE 或 APPEND。 */
    private String operation;

    /** 紧邻前一版本原文的指纹，初版为空。 */
    private String previousFingerprint;

    /** 含检验计划、项目绑定和来源信息的冻结 JSON 原文。 */
    private String payload;

    /** 按本版冻结原文字节计算的 SHA-256。 */
    private String payloadFingerprint;

    /** 创建本版的登录用户 ID；无用户身份的服务调用可为空。 */
    private Long createdBy;

    /** 本版依据形成的业务时间。 */
    private Date occurredAt;

    /** 仅查询时使用的有界链元数据，不是业务表列。 */
    @TableField(exist = false)
    private Long lineageRows;

    /** 当前版本链全部原文的合计字节数，仅用于检查读取上限。 */
    @TableField(exist = false)
    private Long lineageBytes;
}

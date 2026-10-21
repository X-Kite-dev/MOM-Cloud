package com.kitex.mom.qms.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * CAPA关闭后复开的不可变回执，保存旧关闭事实和新周期，不覆盖原验证历史。
 */
@Data
@Accessors(chain = true)
@TenantTable
@TableName("qms_capa_mes_retest_reopen_receipt")
@EqualsAndHashCode(callSuper = true)
public class QmsCapaMesRetestReopenReceiptEntity extends BaseEntity<QmsCapaMesRetestReopenReceiptEntity> {
    @Schema(description = "永久复开请求号")
    private String requestId;

    @Schema(description = "CAPA主键")
    private Long capaId;

    @Schema(description = "经MES独立复核的失败复测主键")
    private Long retestId;

    @Schema(description = "复测编号")
    private String retestNo;

    @Schema(description = "原关闭周期")
    private Integer closedCycleNo;

    @Schema(description = "新围堵周期")
    private Integer reopenedCycleNo;

    @Schema(description = "MES复测完整证据指纹")
    private String evidenceFingerprint;

    @Schema(description = "原CAPA关闭的完整快照，禁止覆盖历史验证")
    private String closedSnapshot;

    @Schema(description = "MES批准复测证明的完整快照")
    private String sourceSnapshot;

    @Schema(description = "MES独立复核人")
    private String reviewedBy;

    @Schema(description = "QMS执行命令的实名账号")
    private String executedBy;

    @Schema(description = "本次复开执行时间")
    private LocalDateTime executedTime;

    @Schema(description = "实际工厂编码")
    private String factoryCode;
}

package com.kitex.mom.mes.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.util.TenantTable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 已成功报工的原始命令永久账。
 *
 * <p>原正文先于条码解析冻结，与报工事实在同一事务中追加。冲销不删除此账，
 * 相同原号只能回放原结果，不能把已释放、已复用的外码解释为另一件实物。</p>
 */
@Data
@TenantTable
@Accessors(chain = true)
@TableName("mes_report_command")
@EqualsAndHashCode(callSuper = true)
@Schema(description = "报工原始命令永久回执")
public class MesReportCommandEntity extends BaseEntity<MesReportCommandEntity> {

    /**
     * 实际授权工厂，不采信客户端正文。
     */
    @Schema(description = "实际工厂编码")
    private String factoryCode;

    /**
     * 请求号在租户内永久唯一，逻辑删除不能释放。
     */
    @Schema(description = "永久报工请求号", maxLength = 64)
    private String requestId;

    /**
     * 原正文算法版本，不以当前算法重新解释旧账。
     */
    @Schema(description = "原正文算法版本")
    private String commandVersion;

    /**
     * 包含算法域前缀的SHA-256，基于解析前的公开业务输入。
     */
    @Schema(description = "原正文指纹")
    private String requestFingerprint;

    /**
     * 稳定字段顺序的原输入JSON；Long身份以字符串存储。
     */
    @Schema(description = "不可变原始业务命令")
    private String commandSnapshot;

    /**
     * 可信服务入口，不允许正文指定渠道。
     */
    @Schema(description = "服务入口 STATION/MOBILE/INTEGRATION/DEVICE")
    private String channel;

    /**
     * 真正承载报工事务的生产工单。
     */
    @Schema(description = "生产工单编码")
    private String taskCode;

    /**
     * 成功时的具体派工身份。
     */
    @Schema(description = "派工单编码")
    private String arrangeCode;

    /**
     * 原始报工事实ID，冲销后仍保留关联。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "原始报工事实ID")
    private Long reportId;

    /**
     * 发起请求的实名账号，与API/设备实际执行主体分别留证。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "实名发起人ID")
    private Long actorId;

    /**
     * 发起账号编码。
     */
    @Schema(description = "实名发起人编码")
    private String actorCode;

    /**
     * 只记录成功受理事实；失败事务不留下伪成功回执。
     */
    @Schema(description = "回执状态 ACCEPTED")
    private String status;

    /**
     * 首次成功时的结果，不因后续冲销改写。
     */
    @Schema(description = "不可变成功回执JSON")
    private String resultSnapshot;

    /**
     * 成功受理时间。
     */
    @Schema(description = "成功受理时间")
    private LocalDateTime acceptedTime;

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }

    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getTenantId() {
        return super.getTenantId();
    }
}

package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 可复用生产载具操作命令。
 *
 * <p>载具只负责逐件成员的装载谱系和批量编排。批量过站时每个生产标识仍进入
 * 标准单件报工事务，不允许把载具总数直接写成一笔不可追溯的数量报工。</p>
 */
@Data
@Schema(description = "可复用生产载具操作命令")
public class MesCarrierCommand {

    @Schema(description = "8至64位永久幂等请求号", requiredMode = Schema.RequiredMode.REQUIRED)
    private String requestId;

    @Schema(description = "操作：CREATE/LOAD/UNLOAD/LOCK/UNLOCK/MAINTAIN/RESTORE/RETIRE")
    private String operation;

    @Schema(description = "载具编码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String carrierCode;

    @Schema(description = "载具类型，例如TRAY/RACK/TOTE/CART")
    private String carrierType;

    @Schema(description = "载具逐件容量")
    private Integer capacity;

    @Schema(description = "装载时用于排除同码跨工单歧义的生产工单号")
    private String taskCode;

    @Schema(description = "装载或拆卸的规范生产标识；拆卸为空时表示全部卸载")
    private List<String> identifiers;

    @Schema(description = "真实操作原因或依据", requiredMode = Schema.RequiredMode.REQUIRED)
    private String reason;

    @Schema(description = "客户端读取的载具状态版本；除创建外必须提供")
    private Integer expectedVersion;
}

package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;

/**
 * 工站恢复换型窗口所需的本地事实观察。
 * 不读取或改变TPM/MDM状态，不是跨服务命令回执，也不证明远端尚未执行。
 */
@Data
@Accessors(chain = true)
@Schema(description = "换型本地事实与实名动作权限")
public class MesChangeoverStateDTO {
    /**
     * 认证链路确定的实际租户，使用文本避免前端长整数损失。
     */
    @Schema(description = "实际租户ID文本")
    private String tenantId;
    /**
     * 当前已授权工厂，不接受客户端替换组织。
     */
    @Schema(description = "实际工厂编码")
    private String factoryCode;
    /**
     * 实名用户ID；仅用于本地等待作用域，不补造历史操作人ID。
     */
    @Schema(description = "当前实名用户ID文本")
    private String actorUserId;
    /**
     * 当前用户名，用于核对既有执行账的操作人字段。
     */
    @Schema(description = "当前登录账号")
    private String account;
    /**
     * 本次观察明确的设备。
     */
    @Schema(description = "设备编码")
    private String deviceCode;
    /**
     * 本次观察明确的工位。
     */
    @Schema(description = "工位编码")
    private String worksiteCode;
    /**
     * 同组织设备唯一进行中账；可能属于另一工位，不能由当前工位直接操作。
     */
    @Schema(description = "设备唯一进行中原账，ID及原时间为文本")
    private Map<String, Object> activeExecution;
    /**
     * 通过执行ID或开始原号精确读取的原账；未找到不等于未提交。
     */
    @Schema(description = "指定执行账或开始原号的本地原事实")
    private Map<String, Object> requestedExecution;
    /**
     * 当前MES工位设备绑定，不代表TPM/MDM远端状态。
     */
    @Schema(description = "当前MES绑定完整事实")
    private Map<String, Object> currentBinding;
    /**
     * 当前人员是否具有开始换型权限。
     */
    @Schema(description = "允许开始换型")
    private boolean canStart;
    /**
     * 当前人员是否具有明确取消权限，事实条件仍由命令重新核对。
     */
    @Schema(description = "允许明确取消")
    private boolean canCancel;
    /**
     * 当前人员是否具有提交完成权限，不表示跨服务结果已知。
     */
    @Schema(description = "允许提交完成")
    private boolean canComplete;
}

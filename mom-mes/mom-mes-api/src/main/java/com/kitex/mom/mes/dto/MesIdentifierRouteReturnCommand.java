package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 单件/流转卡退回上序操作命令。
 *
 * <p>申请时提交生产工单、生产标识和目标工序；复核及撤回只引用申请记录，
 * 服务端始终重新核对当前工序事实，客户端不能直接指定路线版本或恢复工序。</p>
 */
@Data
@Schema(description = "逐标识退回上序操作命令")
public class MesIdentifierRouteReturnCommand {

    @Schema(description = "永久幂等请求号", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 64)
    private String requestId;

    @Schema(description = "申请记录ID；复核、驳回、撤回时必填")
    private Long routeReturnId;

    @Schema(description = "生产工单号；申请时必填", maxLength = 100)
    private String taskCode;

    @Schema(description = "规范生产标识；申请时必填", maxLength = 200)
    private String identifier;

    @Schema(description = "目标上序编码；申请时必填", maxLength = 100)
    private String targetRouteCode;

    @Schema(description = "节点合同的目标实际工序任务；旧申请不得补填", maxLength = 100)
    private String targetProduceTaskCode;

    @Schema(description = "节点合同的目标稳定节点；须与实际任务一致", maxLength = 64)
    private String targetRouteNodeCode;

    @Schema(description = "实际业务原因", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 500)
    private String reason;

    @Schema(hidden = true)
    private String operation;
}

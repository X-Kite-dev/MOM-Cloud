package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 当前请求的节点换码部署/渠道决策，不是派工永久事实或现场验收证明。
 */
@Data
@Accessors(chain = true)
@Schema(description = "动态节点当前就绪诊断；不得写入派工冻结快照")
public class MesIdentifierNodeReadinessDTO {

    /**
     * 终端只识别此版本，未知版本故障关闭。
     */
    @Schema(description = "决策协议版本")
    private String version = "MES_NODE_READINESS_V1";

    /**
     * 核对的是当前认证组织，不接受页面指定组织。
     */
    @Schema(description = "实际租户ID文本")
    private String tenantId;

    /**
     * 本次认证链确定的工厂。
     */
    @Schema(description = "实际工厂编码")
    private String factoryCode;

    /**
     * 用于防止同厂切换账号后复用另一账号的渠道决定。
     */
    @Schema(description = "实际登录账号ID文本")
    private String actorId;

    /**
     * DEPLOYMENT/PUBLISH/REPORT，不把全局结构通过当具体报工许可。
     */
    @Schema(description = "诊断作用域")
    private String scope;

    /**
     * REPORT决定必须指向精确的原派工。
     */
    @Schema(description = "精确派工号，部署/发布诊断为空")
    private String arrangeCode;

    /**
     * 可信后端入口指定，不读取报工正文的type。
     */
    @Schema(description = "执行渠道；部署/发布诊断为空")
    private String channel;

    /**
     * 原冻结契约核定动作；部署诊断为空。
     */
    @Schema(description = "NONE/BIND/USE/RELEASE")
    private String action;

    /**
     * 仅说明实时系统目录中的所需保护已匹配，不说明业务数据/现场验收通过。
     */
    @Schema(description = "当前数据库结构及保护核对通过")
    private Boolean databaseReady = false;

    /**
     * 仅在该决定的身份、作用域、渠道、静态契约及部署均通过时为true。
     */
    @Schema(description = "本作用域前置检查通过；实际生产门仍在提交事务逐项校验")
    private Boolean ready = false;

    /**
     * 实际服务账号缺失的结构或保护；不披露数据库凭据。
     */
    @Schema(description = "数据库保护缺项")
    private List<String> missingControls = new ArrayList<>();

    /**
     * 按当前身份与契约过滤的在线渠道；不是旧IoT/旧弱网通行证。
     */
    @Schema(description = "当前可用渠道")
    private List<String> allowedChannels = new ArrayList<>();

    /**
     * 可直接展示的明确阻断原因，失败查询也必须保留原因。
     */
    @Schema(description = "前置检查阻断原因")
    private List<String> blockedReasons = new ArrayList<>();

    /**
     * 现场结果无法从系统目录推出，不由SQL标记或编译补造。
     */
    @Schema(description = "现场验收状态；本诊断不执行现场验收")
    private String fieldAcceptance = "NOT_VERIFIED_BY_DIAGNOSTIC";
}

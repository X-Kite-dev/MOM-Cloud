package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 移动派工执行契约。身份、数量及时间使用文本，避免终端长整数精度丢失。
 */
public final class MesMobileExecutionDTO {

    private MesMobileExecutionDTO() {
    }

    /**
     * 客户端必须先持久化完整正文；原号重投不能修改任何字段。
     */
    @Data
    @Schema(description = "移动派工原始命令")
    public static class Command {
        /**
         * 租户内永久唯一的原请求号。
         */
        private String requestId;
        /**
         * 精确派工ID，十进制文本。
         */
        private String arrangeId;
        /**
         * RECEIVE/START/PAUSE/RESUME/FINISH，与报工对象模式相互独立。
         */
        private String action;
        /**
         * 从本工作台读取的完整派工事实指纹。
         */
        private String expectedFingerprint;
        /**
         * 实际租户；服务端再次与认证上下文核对。
         */
        private String expectedTenantId;
        /**
         * 实际工厂；不能用于扩大工厂权限。
         */
        private String expectedFactoryCode;
        /**
         * 实名操作人ID；不能由前端代替其他执行员工。
         */
        private String expectedActorUserId;
        /**
         * 接收选定实际工位；其他动作必须匹配原实际工位。
         */
        private String worksiteCode;
        /**
         * 操作原因，1～1000字符。
         */
        private String reason;
    }

    /**
     * 当前派工事实不是门禁绿灯；提交仍执行原有全部领域门。
     */
    @Data
    @Schema(description = "移动派工执行工作台")
    public static class View {
        /**
         * 当前服务端租户、工厂、实名用户，禁止从旧列表拼接。
         */
        private String tenantId;
        /**
         * 实际认证工厂。
         */
        private String factoryCode;
        /**
         * 实际实名用户主键。
         */
        private String actorUserId;
        /**
         * 实际实名账号。
         */
        private String actorCode;
        /**
         * 精确资源身份及稳定本地事实指纹。
         */
        private String arrangeId;
        /**
         * 完整本地派工事实的版本域SHA-256。
         */
        private String factsFingerprint;
        /**
         * 全部本地派工字段，所有非空标量以文本交付。
         */
        private Map<String, String> facts;
        /**
         * 派工冻结契约JSON，数值同样转换为文本，未反推当前模板。
         */
        private String contractSnapshot;
        /**
         * 实际选定工位与候选工位分别提供。
         */
        private String actualWorksiteCode;
        /**
         * 原派工明确允许选择的工位全集。
         */
        private List<Worksite> worksiteCandidates;
        /**
         * 已核定的原菜单权限、执行人、状态和移动开关。
         */
        private List<String> allowedActions;
        /**
         * 各动作不可用原因，不包含未经执行的设备/质量绿灯。
         */
        private Map<String, String> blockedActions;
        /**
         * 同派工未决占用；他人占用不披露原命令正文。
         */
        private Pending pending;
    }

    /**
     * 原派工配置中的一个候选工位。
     */
    @Data
    public static class Worksite {
        /**
         * 候选工位编码。
         */
        private String code;
        /**
         * 原派工对应显示名称，不改变候选身份。
         */
        private String name;
    }

    /**
     * 跨浏览器、跨账号的未决占用提示。
     */
    @Data
    public static class Pending {
        /**
         * 占用派工的永久原号。
         */
        private String requestId;
        /**
         * 原动作，不从当前状态推断。
         */
        private String action;
        /**
         * PREPARED/EXECUTING/UNKNOWN。
         */
        private String status;
        /**
         * 原实名执行账号，便于回到原账号查证。
         */
        private String actorCode;
        /**
         * 是否属于当前实际实名，仅本人可查完整正文。
         */
        private Boolean ownedByCurrentActor;
    }

    /**
     * 原回执只能证明该完整原号，不能用页面当前状态替代。
     */
    @Data
    @Schema(description = "移动派工永久原回执")
    public static class Receipt {
        /**
         * 固定原正文算法版本。
         */
        private String commandVersion;
        /**
         * 原请求号；ABSENT亦明确返回所查询原号。
         */
        private String requestId;
        /**
         * 原实际租户。
         */
        private String tenantId;
        /**
         * 原实际工厂。
         */
        private String factoryCode;
        /**
         * 原实名用户主键。
         */
        private String actorUserId;
        /**
         * 原实名账号。
         */
        private String actorCode;
        /**
         * 原派工主键；ABSENT没有已登记资源事实。
         */
        private String arrangeId;
        /**
         * 原动作。
         */
        private String action;
        /**
         * 原完整正文SHA-256。
         */
        private String requestFingerprint;
        /**
         * 固定顺序九字段完整原正文。
         */
        private String commandSnapshot;
        /**
         * 实际登记时事实指纹；过时拒绝可以不等于原expectedFingerprint。
         */
        private String beforeFactsFingerprint;
        /**
         * 五种永久账状态；ABSENT仅表示本次未观察到原号。
         */
        private String status;
        /**
         * 精确原结果JSON；不得用当前页面状态代替。
         */
        private String resultSnapshot;
        /**
         * 原拒绝或未知说明，不能宣称全域无副作用。
         */
        private String message;
        /**
         * 原正文独立登记时间文本。
         */
        private String registeredTime;
        /**
         * 执行占用独立提交时间文本。
         */
        private String claimedTime;
        /**
         * 原结果登记时间文本，UNKNOWN仍未决。
         */
        private String resolvedTime;
    }
}

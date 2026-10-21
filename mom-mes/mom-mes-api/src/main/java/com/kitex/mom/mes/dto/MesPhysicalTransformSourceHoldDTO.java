package com.kitex.mom.mes.dto;

import lombok.Data;

import java.util.List;

/**
 * 来源占用/取消/恢复专用契约，不接客户端来源包、政策草案或执行放行标记。
 */
public final class MesPhysicalTransformSourceHoldDTO {
    private MesPhysicalTransformSourceHoldDTO() {
    }

    /**
     * 所有ID/时间/身份数量以文本交付；完整原证据不截断。
     */
    @Data
    public static class Receipt {
        /**
         * 原请求号，永久tenant/request身份不按工厂或版本分裂。
         */
        private String requestId;
        /**
         * 原实际命令ID，NONE时仍查询并核对真实原命令。
         */
        private String commandId;
        /**
         * 原命令自身版本SHA。
         */
        private String commandFingerprint;
        /**
         * 永久占用ID；无占用历史时为空。
         */
        private String holdId;
        /**
         * 永久占用算法版本；NONE时为空，不编造事实。
         */
        private String holdVersion;
        /**
         * 实际原租户。
         */
        private String tenantId;
        /**
         * 实际原工厂。
         */
        private String factoryCode;
        /**
         * 实际原本人ID。
         */
        private String actorUserId;
        /**
         * 实际原实名账号。
         */
        private String actorCode;
        /**
         * SOURCE_HELD/CANCELLED/NONE/ABSENT/UNAVAILABLE，只证明实际可读永久事实。
         */
        private String status;
        /**
         * 完整服务器冻结原字符串，不重序列化旧证据。
         */
        private String frozenSnapshot;
        /**
         * 永久原占用SHA，取消必须精确匹配。
         */
        private String frozenFingerprint;
        /**
         * 完整typed identity二元组，数量有显式边界而非分页截断。
         */
        private List<List<String>> identities;
        /**
         * 首次实际占用时间。
         */
        private String heldTime;
        /**
         * 本人取消时间；活动占用为空。
         */
        private String cancelledTime;
        /**
         * 首次原取消原因。
         */
        private String cancelReason;
        /**
         * 固定false；来源保护不等于完整资格/库存/谱系和三端可执行。
         */
        private Boolean executionAvailable;
        /**
         * 当前原事实及响应不明的处置说明。
         */
        private String message;
    }
}

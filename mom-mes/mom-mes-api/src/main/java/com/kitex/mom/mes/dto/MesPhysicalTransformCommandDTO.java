package com.kitex.mom.mes.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 实物转换永久原正文与回执；与只读policyDraft预检协议分开。
 * 登记只保留传输原文，不预占库存，不证明原工艺/来源或实际转换成功。
 */
public final class MesPhysicalTransformCommandDTO {

    private MesPhysicalTransformCommandDTO() {
    }

    /**
     * V1八字段严格命令，也是BodyV2的公共字段；正式政策须由后续锁内原派工取得。
     */
    @Data
    public static class Body {
        /**
         * Body为COMMAND_V1，BodyV2为COMMAND_V2；自身版本参与原字符串SHA，不与预检混用。
         */
        private String version;
        /**
         * 页面观察租户，只与实际租户比对。
         */
        private String expectedTenantId;
        /**
         * 页面观察工厂，只与实际工厂比对。
         */
        private String expectedFactoryCode;
        /**
         * 页面观察本人ID，只与认证用户比对。
         */
        private String expectedActorUserId;
        /**
         * 精确源库存/原观察SHA/投入量，所有ID及数量为文本。
         */
        private List<MesPhysicalTransformDTO.Input> inputs;
        /**
         * 完整新对象/位置/处置，不由服务端猜首项或生成新码。
         */
        private List<MesPhysicalTransformDTO.Output> outputs;
        /**
         * 每源至每输出的完整正量当量边。
         */
        private List<MesPhysicalTransformDTO.Allocation> allocations;
        /**
         * 原业务原因，合法原文不清洗。
         */
        private String reason;
    }

    /**
     * 九字段V2原命令，仅增加观察算法身份；原V1不能包含该字段。
     * 原来源、工程、位置与资格仍由真正领域事务重读，不接受客户端证据包。
     */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class BodyV2 extends Body {
        /**
         * 固定MES_PHYSICAL_SOURCE_V2，不混用旧V1观察SHA或预检版本。
         */
        private String expectedSourceVersion;
    }

    /**
     * 本人显式封存未执行原号；封存不删除原文或释放原号。
     */
    @Data
    public static class Seal {
        /**
         * 精确原正文指纹，不能对另一版正文封存。
         */
        private String expectedFingerprint;
        /**
         * 明确封存原因，保存为独立历史，不覆盖业务原原因。
         */
        private String reason;
    }

    /**
     * 所有Long及时间文本交付；REGISTERED不是PREPARED/APPLIED。
     */
    @Data
    public static class Receipt {
        /**
         * 实际永久账ID；ABSENT时为空。
         */
        private String commandId;
        /**
         * 永久原事实协议版本；ABSENT没有原事实，因此为空。
         */
        private String commandVersion;
        /**
         * 租户内唯一原号。
         */
        private String requestId;
        /**
         * 实际租户ID。
         */
        private String tenantId;
        /**
         * 实际认证工厂。
         */
        private String factoryCode;
        /**
         * 实际原登记者ID。
         */
        private String actorUserId;
        /**
         * 实际原登记者账号。
         */
        private String actorCode;
        /**
         * 原始UTF-8正文版本域SHA-256。
         */
        private String requestFingerprint;
        /**
         * 完整不可变传输原字符串，不能用重新序列化的DTO替换。
         */
        private String commandSnapshot;
        /**
         * REGISTERED/SEALED/APPLIED或ABSENT；APPLIED须查看独立正式执行回执。
         */
        private String status;
        /**
         * 原命令首次登记时间。
         */
        private String registeredTime;
        /**
         * 本人封存时间；未封存时为空。
         */
        private String sealedTime;
        /**
         * 原封存原因；重复封存不覆盖。
         */
        private String sealReason;
        /**
         * 同一原命令的永久来源占用事实；未部署时UNAVAILABLE，不伪造未占用。
         */
        private MesPhysicalTransformSourceHoldDTO.Receipt sourceHold;
        /**
         * 仅APPLIED时为true；REGISTERED/SEALED仍不可据此执行。
         */
        private Boolean executionAvailable;
        /**
         * 状态口径及未知响应的处理要求。
         */
        private String message;
    }
}

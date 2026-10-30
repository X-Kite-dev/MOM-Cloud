package com.kitex.mom.mdm.dto;

import com.kitex.mom.mdm.entity.MdmPhysicalTransformStandardEntity;
import com.kitex.mom.mdm.entity.MdmPhysicalTransformStandardLogEntity;
import lombok.Data;

import java.util.List;

/**
 * 实物转换工程标准协议；身份、版本及计量均按文本传输，不接客户端发布事实。
 */
public final class MdmPhysicalTransformStandardDTO {

    private MdmPhysicalTransformStandardDTO() {
    }

    /**
     * 严格草稿编制正文，首次id/期望指纹为空串；既有版本须精确原指纹。
     */
    @Data
    public static class Draft {
        /**
         * 打开编制页时的期望租户，只与服务端当前上下文比对，不能切换身份。
         */
        private String expectedTenantId;
        /**
         * 打开编制页时的期望工厂，防异步请求头切厂后写入另一工厂。
         */
        private String expectedFactoryCode;
        /**
         * 原标准精确ID，新增为空串。
         */
        private String id;
        /**
         * 显式复制编制的原标准ID，普通新增/修改为空；新配置需用户明确提交。
         */
        private String copySourceId;
        /**
         * 页面读取的原内容指纹，新增为空串。
         */
        private String expectedFingerprint;
        /**
         * 原最近动作ID文本，新增为空串；内容未变也不能跨撤回/重提周期覆盖。
         */
        private String expectedOperationId;
        /**
         * 家族标准编码，首版确定后不可换家族。
         */
        private String standardNo;
        /**
         * 标准中文名称。
         */
        private String standardName;
        /**
         * 当前实际工厂物料编码。
         */
        private String materialCode;
        /**
         * 实际工艺路线ID文本。
         */
        private String processRouteId;
        /**
         * 该路线具体工序编码。
         */
        private String processCode;
        /**
         * 跨单位的实际换算行ID，同单位为空串。
         */
        private String unitConversionId;
        /**
         * 拆板工程布局编码，其他动作为空串；布局版本随本标准版本。
         */
        private String positionLayoutCode;
        /**
         * 严格九字段政策JSON文本；不是发布许可。
         */
        private String policyJson;
        /**
         * 本次编制/修改的业务说明，不裁剪。
         */
        private String reason;
    }

    /**
     * 完整动作正文；SHA防止迟到审批或页面静默审核他人新修改。
     */
    @Data
    public static class Action {
        /**
         * 原页面期望租户，不作为服务端归属来源。
         */
        private String expectedTenantId;
        /**
         * 原页面期望工厂，当前认证上下文变化则拒绝。
         */
        private String expectedFactoryCode;
        /**
         * 当前标准精确ID。
         */
        private String id;
        /**
         * 原内容SHA-256。
         */
        private String expectedFingerprint;
        /**
         * 页面读取的最近永久动作ID；拒绝同正文的迟到审批。
         */
        private String expectedOperationId;
        /**
         * SUBMIT、WITHDRAW、REJECT、RELEASE、RETIRE、COPY。
         */
        private String action;
        /**
         * 操作原因，必须明确。
         */
        private String reason;
    }

    /**
     * 只解析精确已发布版本，不按最新版本补造历史。
     */
    @Data
    public static class ResolveQuery {
        /**
         * 标准精确ID文本。
         */
        private String standardId;
        /**
         * 与真实上下文比对的工厂。
         */
        private String factoryCode;
        /**
         * 与发布正文比对的物料。
         */
        private String materialCode;
        /**
         * 与发布正文比对的实际路线ID。
         */
        private String processRouteId;
        /**
         * 与发布正文比对的实际路线编码。
         */
        private String processRouteCode;
        /**
         * 实际工序编码。
         */
        private String processCode;
        /**
         * 需要匹配的严格九字段政策JSON。
         */
        private String policyJson;
    }

    /**
     * 发布原内容及真实发布身份；MES须在派工边界永久冻结，不调用当前依赖改写。
     */
    @Data
    public static class Frozen {
        /**
         * 工程协议版本。
         */
        private String version;
        /**
         * 原标准ID文本。
         */
        private String standardId;
        /**
         * 实际租户ID。
         */
        private String tenantId;
        /**
         * 实际工厂。
         */
        private String factoryCode;
        /**
         * 不可变完整工程正文，含实际依赖和布局政策。
         */
        private String contentSnapshot;
        /**
         * 版本域SHA-256。
         */
        private String contentFingerprint;
        /**
         * 真正发布人ID文本。
         */
        private String releasedById;
        /**
         * 发布人实名账号。
         */
        private String releasedBy;
        /**
         * 首次发布时间文本，不是本次查询时间。
         */
        private String releasedTime;
    }

    /**
     * 版本详情及永久生命周期履历。
     */
    @Data
    public static class Detail {
        /**
         * 精确版本完整记录。
         */
        private MdmPhysicalTransformStandardEntity standard;
        /**
         * 全部该版本履历，不能只选首条编制人。
         */
        private List<MdmPhysicalTransformStandardLogEntity> logs;
    }

    /**
     * 分页实际依据选择；身份/比例不经浏览器浮点，详情原JSON供人工核对。
     */
    @Data
    public static class BasisOption {
        /**
         * 实际基础行精确ID文本。
         */
        private String id;
        /**
         * 实际物料编码。
         */
        private String materialCode;
        /**
         * 路线/工序编码，换算行显示真实单位对。
         */
        private String code;
        /**
         * 实际名称，不作为身份。
         */
        private String name;
        /**
         * 非标准单位，路线/工序为空串。
         */
        private String inputUnit;
        /**
         * 标准单位，路线/工序为空串。
         */
        private String outputUnit;
        /**
         * 原换算比例文本，不能用近似倒数替换。
         */
        private String ratio;
        /**
         * 完整实际基础记录JSON，非正式发布正文。
         */
        private String detailJson;
    }
}

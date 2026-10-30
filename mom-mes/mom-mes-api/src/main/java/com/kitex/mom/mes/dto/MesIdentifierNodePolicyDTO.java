package com.kitex.mom.mes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 工艺区段的动态标识策略。它是单件报工的节点动作，不是另一种报工对象模式。
 *
 * <p>同组的进入、使用和释放节点共享定义；动作及冻结依据只能由服务端确定。
 * 内部规范SN仍是追溯主键，外码只在活动区段内用于识别该产品。</p>
 */
@Data
@Accessors(chain = true)
@Schema(description = "工艺节点动态外码策略与冻结依据")
public class MesIdentifierNodePolicyDTO {

    /**
     * 旧策略缺字段时为NONE，不能读取当前模板反推历史动作。
     */
    @Schema(description = "节点动作：NONE/BIND/USE/RELEASE，旧策略默认NONE")
    private String action = "NONE";

    /**
     * 在真实工单内标识一个完整绑定至释放区段的稳定编码。
     */
    @Schema(description = "工单内换码区段编码，最长100字符")
    private String bindingGroupCode;

    /**
     * 同租户工厂内外码占用的命名空间，不由事件临时覆盖。
     */
    @Schema(description = "外码命名空间，最长100字符")
    private String externalNamespace;

    /**
     * 独立内容协议；NULL保留旧主工序策略，不能替旧采集补版本或空数组。
     */
    @Schema(description = "策略内容协议：NODE_BINDING_V2；历史策略为空")
    private String policySchemaVersion;

    /**
     * 原生节点所在工程路线，不与节点的主工序routeCode混用。
     */
    @Schema(description = "原生策略所属工艺路线编码")
    private String processRouteCode;

    /**
     * 原生区段唯一绑定节点的稳定身份。
     */
    @Schema(description = "绑定节点编码，最长64字符")
    private String bindRouteNodeCode;

    /**
     * 原生全部使用节点；NULL仅供旧协议，不能初始化空列表改变旧JSON。
     */
    @Schema(description = "原生区段全部使用节点编码")
    private List<String> useRouteNodeCodes;

    /**
     * 原生区段全部分支最终通过的释放节点。
     */
    @Schema(description = "释放节点编码，成功过站后才释放外码")
    private String releaseRouteNodeCode;

    /**
     * 本组唯一绑定节点的工序编码。
     */
    @Schema(description = "绑定工序编码")
    private String bindRouteCode;

    /**
     * 区段内全部使用工序编码，允许并行；不以列表顺序推断工艺先后。
     */
    @Schema(description = "区段全部使用工序编码，工艺先后由工单DAG确定")
    private List<String> useRouteCodes = new ArrayList<>();

    /**
     * 全部区段路径通过的唯一释放工序编码。
     */
    @Schema(description = "释放工序编码，成功过站后才释放外码")
    private String releaseRouteCode;

    /**
     * 使用/释放时是否允许内部码；即使允许仍须核对活动关系。
     */
    @Schema(description = "使用输入：EXTERNAL_REQUIRED或EITHER，绑定节点始终需内部SN与外码")
    private String inputMode = "EXTERNAL_REQUIRED";

    /**
     * 可选字面前缀，不执行正则脚本或客户专用替换。
     */
    @Schema(description = "可选外码字面前缀，最长80字符")
    private String externalCodePrefix;

    /**
     * 完整外码的最短长度。
     */
    @Schema(description = "外码最短长度，1至200")
    private Integer externalCodeMinLength = 1;

    /**
     * 完整外码的最大长度，包含前缀。
     */
    @Schema(description = "外码最长长度，不超过200")
    private Integer externalCodeMaxLength = 200;

    /**
     * 永久工单区段主键，不能由草稿、终端或设备指定。
     */
    @Schema(description = "服务端永久冻结区段ID文本", accessMode = Schema.AccessMode.READ_ONLY)
    private String bindingGroupId;

    /**
     * 首次派工确定的永久定义版本；重新发布模板不能替换它。
     */
    @Schema(description = "服务端永久冻结区段版本", accessMode = Schema.AccessMode.READ_ONLY)
    private Integer bindingGroupVersion;

    /**
     * 同组定义指纹不含当前动作，供三个角色的模板比较。
     */
    @Schema(description = "服务端生成的组定义SHA-256指纹", accessMode = Schema.AccessMode.READ_ONLY)
    private String definitionFingerprint;

    /**
     * 工单实际规范化工序图指纹，不含数量、状态或执行时刻。
     */
    @Schema(description = "服务端冻结的完整工序图指纹", accessMode = Schema.AccessMode.READ_ONLY)
    private String graphFingerprint;

    /**
     * 原工单路线JSON的独立来源指纹，不当作规范化图。
     */
    @Schema(description = "服务端冻结的原路线来源指纹", accessMode = Schema.AccessMode.READ_ONLY)
    private String sourceRouteFingerprint;

    /**
     * 区段真实工序任务与边，ID和工序任务号以文本交付。
     */
    @Schema(description = "服务端冻结的区段工序任务与DAG边", accessMode = Schema.AccessMode.READ_ONLY)
    private List<Node> frozenNodes = new ArrayList<>();

    /**
     * 不包含执行数量、人员或实时时间的不可变工艺节点依据。
     */
    @Data
    @Accessors(chain = true)
    @Schema(description = "换码区段冻结工序依据")
    public static class Node {

        /**
         * 工序任务主键的十进制文本，避免JavaScript Long精度损失。
         */
        @Schema(description = "工序任务ID文本")
        private String produceTaskId;

        /**
         * 同一工单生成的正式工序任务号。
         */
        @Schema(description = "工序任务号")
        private String produceTaskCode;

        /**
         * 正式工序编码。
         */
        @Schema(description = "工序编码")
        private String routeCode;

        /**
         * 来自规范化工序任务的全部直接前序编码。
         */
        @Schema(description = "全部直接前序工序")
        private List<String> predecessorCodes;

        /**
         * 来自规范化工序任务的全部直接后序编码。
         */
        @Schema(description = "全部直接后序工序")
        private List<String> successorCodes;

        /**
         * 明确原生协议下的稳定节点，历史五字段快照不补造。
         */
        @Schema(description = "来源工程节点编码，历史协议为空")
        private String sourceRouteNodeCode;

        /**
         * 完整实际图的直接前序节点；原生数组权威，旧工序投影只作兼容描述。
         */
        @Schema(description = "原生直接前序节点编码")
        private List<String> predecessorNodeCodes;

        /**
         * 完整实际图的直接后序节点。
         */
        @Schema(description = "原生直接后序节点编码")
        private List<String> successorNodeCodes;
    }
}

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
 * 首次派工冻结的永久换码区段。三种节点角色共用一份定义，不随模板换版或外码释放改写。
 *
 * <p>它不是活动绑定实例；不能通过编辑此账跳过绑定、改变工艺图或替换历史契约。</p>
 */
@Data
@TenantTable
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@TableName("mes_identifier_binding_group")
@Schema(description = "工单动态换码永久冻结区段")
public class MesIdentifierBindingGroupEntity extends BaseEntity<MesIdentifierBindingGroupEntity> {

    /**
     * 当前认证链路确定的实际工厂，不接受调用者换厂。
     */
    @Schema(description = "实际工厂编码")
    private String factoryCode;

    /**
     * 物理生产工单主键；同编码历史不能冒充同一张工单。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "生产工单ID文本")
    private Long taskId;

    /**
     * 冻结时的正式生产工单号。
     */
    @Schema(description = "生产工单号")
    private String taskCode;

    /**
     * 同一实际工单的稳定区段编码。
     */
    @Schema(description = "换码区段编码，最长100字符")
    private String bindingGroupCode;

    /**
     * 当前工厂内外码占用域，不能由报工事件临时覆盖。
     */
    @Schema(description = "外码命名空间，最长100字符")
    private String externalNamespace;

    /**
     * 永久定义首版固定为1，不允许覆盖式换版。
     */
    @Schema(description = "永久区段定义版本")
    private Integer versionNo;

    /**
     * 区段只冻结ONE_CODE单件链，不把批量或载具当单件。
     */
    @Schema(description = "报工对象模式，首版仅ONE_CODE")
    private String reportMode;

    /**
     * 定义账状态固定FROZEN，与关系的ACTIVE/RELEASED无关。
     */
    @Schema(description = "永久定义状态：FROZEN")
    private String status;

    /**
     * 全部区段入口的正式工序编码。
     */
    @Schema(description = "绑定工序编码")
    private String bindRouteCode;

    /**
     * 全部区段出口的正式工序编码。
     */
    @Schema(description = "释放工序编码")
    private String releaseRouteCode;

    /** 内容协议独立于永久组业务版本；旧账为NULL，禁止自动迁移。 */
    @Schema(description = "原生内容协议NODE_BINDING_V2，历史组为空")
    private String policySchemaVersion;

    /** 原生节点所属的工程路线编码，与主工序编码分离。 */
    @Schema(description = "原生组所属工艺路线编码")
    private String processRouteCode;

    /** 原生唯一绑定节点；主工序相同也不能与释放节点相同。 */
    @Schema(description = "绑定节点编码")
    private String bindRouteNodeCode;

    /** 原生唯一释放节点，所有区段路径均须经过。 */
    @Schema(description = "释放节点编码")
    private String releaseRouteNodeCode;

    /**
     * 不含当前角色的规范化公开定义JSON，供BIND/USE/RELEASE共同核对。
     */
    @Schema(description = "不可变组定义JSON，不含当前动作")
    private String definitionSnapshot;

    /**
     * 完整实际工序任务图，含全部节点及直接前后序；ID均为文本。
     */
    @Schema(description = "完整实际工单DAG快照JSON")
    private String graphSnapshot;

    /**
     * 完整闭合区段的实际节点集合，不以CSV位置推断工艺顺序。
     */
    @Schema(description = "区段节点快照JSON")
    private String regionSnapshot;

    /**
     * 原工单的路线来源原文，保留和规范化图独立的证据。
     */
    @Schema(description = "原始工单路线来源JSON")
    private String sourceRouteSnapshot;

    /**
     * MES_NODE_GROUP_V1组定义SHA-256。
     */
    @Schema(description = "组定义SHA-256指纹")
    private String definitionFingerprint;

    /**
     * MES_NODE_GRAPH_V1完整实际图SHA-256。
     */
    @Schema(description = "完整实际图SHA-256指纹")
    private String graphFingerprint;

    /**
     * MES_NODE_ROUTE_SOURCE_V1原始来源SHA-256。
     */
    @Schema(description = "原始路线来源SHA-256指纹")
    private String sourceRouteFingerprint;

    /**
     * 实际首次派工人员主键。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "冻结人员ID文本")
    private Long frozenById;

    /**
     * 实际首次派工人员账号，不使用终端声明的操作人。
     */
    @Schema(description = "冻结人员账号")
    private String frozenByCode;

    /**
     * 实际首次派工人员姓名。
     */
    @Schema(description = "冻结人员姓名")
    private String frozenByName;

    /**
     * 服务端首次冻结时间，后续派工不重写。
     */
    @Schema(description = "首次冻结时间")
    private LocalDateTime frozenTime;

    /**
     * 显式交付文本主键，不依赖特定全局Jackson配置。
     */
    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getId() {
        return super.getId();
    }

    /**
     * 显式交付文本租户ID，避免前端Long精度损失。
     */
    @Override
    @JsonSerialize(using = ToStringSerializer.class)
    public Long getTenantId() {
        return super.getTenantId();
    }
}

package com.kitex.mom.mdm.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.kitex.mom.mdm.entity.MdmProcessRouteWorkingEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 工艺路线-工序DTO
 *
 * @author kitex
 * @date 2025-03-26 10:23:27
 */
@Data
public class MdmProcessRouteProcedureDTO {

    /** 本次编辑读取的原图版本；用于拒绝旧页面覆盖新图，不能用当前版本代填重试。 */
    @Schema(description = "编辑时读取的路线图版本，必填")
    private Long expectedGraphRevision;

    /** 独立节点图协议，新管理端固定ROUTE_NODE_GRAPH_V2。 */
    @Schema(description = "图保存协议")
    private String graphSchemaVersion;

    /** 新页面明确新增的主工序；用于区分删除重加与旧请求省略身份的唯一桥接。 */
    @Schema(description = "本次明确新增节点的主工序编码集合；仍不允许重复主工序")
    private List<String> newProcessCodes;

    /**
     * ID
     */
    @Schema(description = "ID")
    private Long id;

    /**
     * 工艺路线编码
     */
    @Schema(description = "工艺路线编码")
    private String routeCode;

    /**
     * 工艺路线名称
     */
    @Schema(description = "工艺路线名称")
    private String routeName;

    /**
     * 工序快照
     */
    @Schema(description = "工序快照")
    private JsonNode procedureSnapshot;

    /**
     * 工序列表
     */
    @Schema(description = "工序列表")
    private List<MdmProcessRouteWorkingEntity> procedureList;

}

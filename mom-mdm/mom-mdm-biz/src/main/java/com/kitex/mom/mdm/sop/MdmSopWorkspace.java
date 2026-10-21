package com.kitex.mom.mdm.sop;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.dto.MdmFormalSopDTO;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.mapper.*;
import com.kitex.mom.mdm.support.MdmRouteNodeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.kitex.mom.mdm.sop.MdmSopSupport.*;

/**
 * 工序与统一SOP的上下文桥接。来源账只记录“从哪里编制”，真正的生产适用关系仍由绑定服务批准。
 * 本组件不依赖版本Service，避免与版本生命周期形成循环注入。
 */
@Component
@RequiredArgsConstructor
public class MdmSopWorkspace {
    private final MdmRouteNodeResolver resolver;
    private final MdmSopSupport support;
    private final MdmSopOriginMapper origins;
    private final MdmSopVersionMapper versions;
    private final MdmProcessProcedureSopMapper attachments;
    private final MdmSopLegacyFileClient files;
    private final MdmSopOriginalStorage storage;
    private final MdmSopOriginalWriter writer;

    /** 出网标识使用字符串，避免浏览器Long精度损失。 */
    public record NodeContext(String id, String routeCode, String routeNodeCode, String processCode, String processName) { }
    /** 历史附件列表不返回私有对象路径；旧公开下载URL仍由原附件页面处理。 */
    public record LegacyItem(String id, String name, String expectedHash) { }

    public NodeContext context(String nodeId) {
        support.access(false);
        var node = resolver.byId(id(nodeId, "节点ID"), false);
        return new NodeContext(node.getId().toString(), node.getRouteCode(), node.getRouteNodeCode(),
                node.getProcessCode(), node.getProcessName());
    }

    /** 节点编制和当前适用分开查询；关联过滤在数据库完成，不能在当前分页上过滤。 */
    public Page<MdmSopVersionEntity> page(String nodeId, String mode, String keyword, String status, long current, long size) {
        var node = context(nodeId);
        require(Set.of("AUTHORED", "BOUND").contains(mode), "请选择当前适用或本工序编制目录");
        require(current > 0 && size > 0 && size <= 50, "SOP目录每页1至50条");
        var query = new LambdaQueryWrapper<MdmSopVersionEntity>()
                .eq(MdmSopVersionEntity::getTenantId, support.tenant())
                .eq(MdmSopVersionEntity::getFactoryCode, support.access(false));
        String table = "BOUND".equals(mode) ? "mdm_sop_binding" : "mdm_sop_origin";
        query.apply("EXISTS (SELECT 1 FROM " + table + " src WHERE src.version_id=mdm_sop_version.id"
                + " AND src.tenant_id={0} AND src.factory_code={1} AND src.route_code={2} AND src.route_node_code={3}"
                + " AND src.del_flag='0'" + ("BOUND".equals(mode) ? " AND src.status='BOUND'" : "") + ")",
                support.tenant(), support.access(false), node.routeCode(), node.routeNodeCode());
        if (keyword != null && !keyword.isBlank()) {
            String term = text(keyword, 100, "关键词", false);
            query.and(q -> q.like(MdmSopVersionEntity::getSopNo, term).or().like(MdmSopVersionEntity::getName, term));
        }
        query.eq(status != null && !status.isBlank(), MdmSopVersionEntity::getStatus, status);
        return versions.selectPage(new Page<>(current, size), query.orderByDesc(MdmSopVersionEntity::getId));
    }

    public Page<LegacyItem> legacy(String nodeId, long current, long size) {
        support.access(false);
        require(current > 0 && size > 0 && size <= 50, "附件目录每页1至50条");
        var node = resolver.byId(id(nodeId, "节点ID"), false);
        var page = attachments.selectPage(new Page<>(current, size), legacyScope(node).orderByAsc(MdmProcessProcedureSopEntity::getId));
        var result = new Page<LegacyItem>(current, size, page.getTotal());
        result.setRecords(page.getRecords().stream().map(row -> new LegacyItem(row.getId().toString(), row.getName(), legacyHash(row))).toList());
        return result;
    }

    public Page<MdmSopOriginEntity> origins(String versionId, long current, long size) {
        support.access(false);
        require(current > 0 && size > 0 && size <= 50, "来源目录每页1至50条");
        return origins.selectPage(new Page<>(current, size), originScope()
                .eq(MdmSopOriginEntity::getVersionId, id(versionId, "SOP版本ID")).orderByDesc(MdmSopOriginEntity::getId));
    }

    /** 由版本服务取得族锁后调用；与绑定服务统一为“版本族→路线”的锁顺序。 */
    public MdmSopOriginEntity prepare(MdmFormalSopDTO.Command command) {
        boolean hasNode = command.getSourceNodeId() != null && !command.getSourceNodeId().isBlank();
        boolean legacy = command.getLegacyAttachmentId() != null && !command.getLegacyAttachmentId().isBlank();
        require(hasNode || !legacy, "历史附件转入必须携带原工序节点");
        MdmSopOriginEntity inherited = null;
        if (!hasNode && "REVISE".equals(command.getAction())) {
            inherited = origins.selectOne(originScope().eq(MdmSopOriginEntity::getVersionId,
                    id(command.getVersionId(), "来源版本ID")));
        }
        if (!hasNode && command.getDerivedFromVersionId() == null && inherited == null) return null;
        var origin = new MdmSopOriginEntity();
        origin.setTenantId(support.tenant());
        origin.setFactoryCode(support.access(true));
        origin.setRequestId(command.getRequestId());
        origin.setSourceType("REVISE".equals(command.getAction()) ? "REVISION" : "NODE_CREATE");
        MdmProcessRouteWorkingEntity node = null;
        if (hasNode) {
            node = resolver.byId(id(command.getSourceNodeId(), "节点ID"), true);
            origin.setRouteCode(node.getRouteCode());
            origin.setRouteNodeCode(node.getRouteNodeCode());
            origin.setProcessCode(node.getProcessCode());
        } else if (inherited != null) {
            // 发起节点被删除后仍保留原编制上下文；不自动恢复节点，也不建立适用绑定。
            origin.setRouteCode(inherited.getRouteCode());
            origin.setRouteNodeCode(inherited.getRouteNodeCode());
            origin.setProcessCode(inherited.getProcessCode());
        }
        if (legacy) {
            require("CREATE".equals(command.getAction()) && command.getDerivedFromVersionId() == null,
                    "历史附件转入仅用于新建统一SOP");
            var old = attachments.selectOne(legacyScope(node).eq(MdmProcessProcedureSopEntity::getId,
                    id(command.getLegacyAttachmentId(), "附件ID")));
            require(old != null && Objects.equals(command.getLegacyExpectedHash(), legacyHash(old)),
                    "历史附件已变化或不属于当前节点，请刷新后重新选择");
            require(Set.of("COPY", "SUPPLEMENT").contains(Objects.toString(command.getLegacyMode(), "")), "请选择转入原文件或人工补充原件");
            origin.setLegacySnapshot(JSONUtil.toJsonStr(old));
            // 文件对象键才是旧附件身份，显示名称或下载URL变化不能绕过转入去重。
            String legacyObject = old.getFileName() == null || old.getFileName().isBlank()
                    ? "MISSING_OBJECT:" + old.getId() : old.getFileName();
            origin.setLegacyKey(hash("SOP_LEGACY_ORIGIN_V1", old.getTenantId(), old.getFactoryCode(),
                    old.getRouteCode(), old.getRouteNodeCode(), legacyObject));
            var previous = origins.selectOne(originScope().eq(MdmSopOriginEntity::getLegacyKey, origin.getLegacyKey()));
            require(previous == null, "该历史附件已纳入统一管理，请在本工序编制目录查看，勿重复创建");
            origin.setSourceType("COPY".equals(command.getLegacyMode()) ? "LEGACY_COPY" : "LEGACY_SUPPLEMENT");
            if ("COPY".equals(command.getLegacyMode())) {
                require(command.getOriginalId() == null || command.getOriginalId().isBlank(), "读取旧文件时不能同时指定其他原件");
                try (var response = files.original(old.getFileName())) {
                    require(response.status() == 200 && response.body() != null, "历史文件读取失败，请检查Admin服务及受控文件接口，或选择人工补充原件");
                    try (var input = response.body().asInputStream()) {
                        byte[] bytes = input.readNBytes(MdmSopOriginalStorage.MAX_BYTES + 1);
                        var original = writer.save(storage.inspect(old.getName(), "application/octet-stream", bytes));
                        command.setOriginalId(original.getId().toString());
                    }
                } catch (CheckedException failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new CheckedException("历史原件读取不可用，请检查Admin服务或人工补充正式原件");
                }
            } else {
                require(command.getOriginalId() != null && !command.getOriginalId().isBlank(), "请上传补充原件；系统不宣称其与旧文件字节一致");
            }
        }
        return origin;
    }

    /** 与新版本同事务落库。来源是永久事实，不随着旧附件编辑或解绑而删除。 */
    public void record(MdmSopOriginEntity origin, MdmSopVersionEntity version, Long sourceVersionId) {
        if (origin == null) return;
        origin.setVersionId(version.getId());
        origin.setOriginalHash(version.getOriginalHash());
        origin.setSourceVersionId(sourceVersionId);
        if (sourceVersionId != null && !"REVISION".equals(origin.getSourceType())) origin.setSourceType("DEDICATED");
        require(origins.insert(origin) == 1, "SOP编制来源保存失败，草稿未提交");
    }

    /** 只对业务文件属性计算指纹，审计时间变化不能伪装成文件变化。 */
    public static String legacyHash(MdmProcessProcedureSopEntity row) {
        return hash("SOP_LEGACY_ATTACHMENT_V1", row.getId(), row.getTenantId(), row.getFactoryCode(),
                row.getRouteCode(), row.getRouteNodeCode(), row.getName(), row.getFileName(), row.getUrl());
    }

    private LambdaQueryWrapper<MdmSopOriginEntity> originScope() {
        return new LambdaQueryWrapper<MdmSopOriginEntity>().eq(MdmSopOriginEntity::getTenantId, support.tenant())
                .eq(MdmSopOriginEntity::getFactoryCode, support.access(false));
    }

    private LambdaQueryWrapper<MdmProcessProcedureSopEntity> legacyScope(MdmProcessRouteWorkingEntity node) {
        return new LambdaQueryWrapper<MdmProcessProcedureSopEntity>().eq(MdmProcessProcedureSopEntity::getTenantId, node.getTenantId())
                .eq(MdmProcessProcedureSopEntity::getFactoryCode, node.getFactoryCode())
                .eq(MdmProcessProcedureSopEntity::getRouteCode, node.getRouteCode())
                .eq(MdmProcessProcedureSopEntity::getRouteNodeCode, node.getRouteNodeCode());
    }
}

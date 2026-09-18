package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.entity.MesScrapMaterialEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.dto.MesQualityDispositionDTO;
import com.kitex.mom.mes.enums.MesSourceQuantityKind;
import com.kitex.mom.mes.mapper.MesScrapMaterialMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.service.MesApsSourceQuantityService;
import com.kitex.mom.mes.service.MesScrapMaterialService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 报废品管理服务实现
 *
 * @author system
 * @since 2025-01-27
 */
@Service
@RequiredArgsConstructor
public class MesScrapMaterialServiceImpl extends ServiceImpl<MesScrapMaterialMapper, MesScrapMaterialEntity> implements MesScrapMaterialService {

    private final MesTaskMapper taskMapper;
    private final MesApsSourceQuantityService sourceQuantityService;

    @Override
    public IPage<MesScrapMaterialEntity> queryPage(Page<MesScrapMaterialEntity> pageParam, MesScrapMaterialEntity query) {
        QueryWrapper<MesScrapMaterialEntity> queryWrapper = new QueryWrapper<>();

        // 添加额外的查询条件
        queryWrapper.lambda()
                .like(StrUtil.isNotBlank(query.getMatCode()), MesScrapMaterialEntity::getMatCode, query.getMatCode())
                .like(StrUtil.isNotBlank(query.getMatName()), MesScrapMaterialEntity::getMatName, query.getMatName())
                .like(StrUtil.isNotBlank(query.getOperator()), MesScrapMaterialEntity::getOperator, query.getOperator())
                .like(StrUtil.isNotBlank(query.getWorkOrder()), MesScrapMaterialEntity::getWorkOrder, query.getWorkOrder())
                .like(StrUtil.isNotBlank(query.getApsBatchNo()), MesScrapMaterialEntity::getApsBatchNo, query.getApsBatchNo())
                .eq(StrUtil.isNotBlank(query.getShift()), MesScrapMaterialEntity::getShift, query.getShift())
                .eq(StrUtil.isNotBlank(query.getMaterialType()), MesScrapMaterialEntity::getMaterialType, query.getMaterialType())
                .eq(StrUtil.isNotBlank(query.getSourceType()), MesScrapMaterialEntity::getSourceType, query.getSourceType())
                .eq(StrUtil.isNotBlank(query.getStatus()), MesScrapMaterialEntity::getStatus, query.getStatus())
                .orderByDesc(MesScrapMaterialEntity::getCreateTime);

        return this.page(pageParam, queryWrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(MesScrapMaterialEntity entity) {
        validateDraft(entity);
        String requestId = StrUtil.blankToDefault(StrUtil.trim(entity.getRequestId()), UUID.randomUUID().toString());
        if (requestId.length() > 64) throw new CheckedException("报废记录幂等号不能超过64位");
        String fingerprint = fingerprint(entity);
        baseMapper.lockRequest("MES_SCRAP:" + requestId);
        MesScrapMaterialEntity existing = this.getOne(new LambdaQueryWrapper<MesScrapMaterialEntity>()
                .eq(MesScrapMaterialEntity::getRequestId, requestId), false);
        if (existing != null) {
            if (!Objects.equals(existing.getRequestFingerprint(), fingerprint)) {
                throw new CheckedException("报废记录幂等号已使用且载荷不一致");
            }
            return;
        }
        bindSourceTask(entity);
        entity.setId(null);
        entity.setRequestId(requestId).setRequestFingerprint(fingerprint)
                .setSourceType("MANUAL").setSourceReference(null)
                .setStatus("0").setAuditVersion(0).setAuditor(null).setAuditTime(null);
        if (!this.save(entity)) throw new CheckedException("报废记录保存失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void edit(MesScrapMaterialEntity entity) {
        if (entity == null || entity.getId() == null) throw new CheckedException("报废记录ID不能为空");
        MesScrapMaterialEntity current = baseMapper.selectOne(new LambdaQueryWrapper<MesScrapMaterialEntity>()
                .eq(MesScrapMaterialEntity::getId, entity.getId()).last("FOR UPDATE"));
        if (current == null) throw new CheckedException("报废品记录不存在");
        if (!"0".equals(current.getStatus())) throw new CheckedException("已审核报废记录不能修改，请先撤销审核");
        validateDraft(entity);
        bindSourceTask(entity);
        entity.setRequestId(current.getRequestId()).setRequestFingerprint(fingerprint(entity))
                .setSourceType(current.getSourceType()).setSourceReference(current.getSourceReference())
                .setStatus("0").setAuditVersion(Objects.requireNonNullElse(current.getAuditVersion(), 0))
                .setAuditor(null).setAuditTime(null);
        if (!this.updateById(entity)) throw new CheckedException("报废记录更新失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePending(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        List<MesScrapMaterialEntity> rows = this.listByIds(ids);
        if (rows.size() != ids.stream().distinct().count()) throw new CheckedException("部分报废记录不存在");
        if (rows.stream().anyMatch(item -> !"0".equals(item.getStatus()))) {
            throw new CheckedException("已审核报废记录不能删除，请先撤销审核");
        }
        if (!this.removeByIds(ids)) throw new CheckedException("报废记录删除失败");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createFromQualityDisposition(MesQualityDispositionDTO command) {
        if (command == null || !"SCRAP".equals(command.getDispositionKind())
                || StrUtil.isBlank(command.getRequestId()) || StrUtil.isBlank(command.getSourceTaskCode())
                || StrUtil.isBlank(command.getFactoryCode())
                || StrUtil.isBlank(command.getMatCode()) || command.getQuantity() == null
                || command.getQuantity().signum() <= 0 || StrUtil.isBlank(command.getReason())) {
            throw new CheckedException("QMS报废同步必须包含请求号、原工单、物料、正数数量和原因");
        }
        String requestId = command.getRequestId().trim();
        if (requestId.length() > 64) throw new CheckedException("QMS报废同步请求号不能超过64位");
        String fingerprint = qualityFingerprint(command);
        baseMapper.lockRequest("MES_SCRAP:" + requestId);
        MesScrapMaterialEntity existing = this.getOne(new LambdaQueryWrapper<MesScrapMaterialEntity>()
                .eq(MesScrapMaterialEntity::getRequestId, requestId), false);
        if (existing != null) {
            if (!Objects.equals(existing.getRequestFingerprint(), fingerprint)) {
                throw new CheckedException("QMS报废同步请求号已使用且载荷不一致");
            }
            return;
        }
        MesTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getCode, command.getSourceTaskCode().trim())
                .eq(MesTaskEntity::getFactoryCode, command.getFactoryCode().trim()));
        if (task == null) throw new CheckedException("QMS报废同步的原生产工单不存在");
        if (!Objects.equals(task.getMatCode(), command.getMatCode())) {
            throw new CheckedException("QMS报废物料与原生产工单产品不一致");
        }
        MesTaskEntity quantitySourceTask = task;
        if ("REWORK".equals(task.getResourceType()) && StrUtil.isNotBlank(task.getSourceTaskCode())) {
            quantitySourceTask = taskMapper.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
                    .eq(MesTaskEntity::getCode, task.getSourceTaskCode()));
            if (quantitySourceTask == null || !Objects.equals(task.getFactoryCode(), quantitySourceTask.getFactoryCode())
                    || !Objects.equals(task.getMatCode(), quantitySourceTask.getMatCode())) {
                throw new CheckedException("返工报废无法解析同工厂、同产品的原生产工单");
            }
        }
        taskMapper.acquireApsBatchTransactionLock("SCRAP_QUANTITY:" + task.getCode());
        BigDecimal approved = approvedScrap(task.getId());
        if (task.getNoticeNum() == null || approved.add(command.getQuantity()).compareTo(task.getNoticeNum()) > 0) {
            throw new CheckedException("QMS报废后工单累计审批报废数量超过工单数量");
        }
        String sourceType = "QMS_" + StrUtil.blankToDefault(command.getInspectionType(), "PRODUCTION")
                .trim().toUpperCase();
        MesScrapMaterialEntity entity = new MesScrapMaterialEntity()
                .setRequestId(requestId).setRequestFingerprint(fingerprint)
                .setMatCode(command.getMatCode()).setMatName(command.getMatName()).setMatSpec(command.getMatSpec())
                .setQuantity(command.getQuantity()).setOperator(StrUtil.blankToDefault(command.getOperatorName(), "QMS"))
                .setWorkOrder(task.getCode()).setScrapTime(Objects.requireNonNullElse(command.getOccurredTime(), LocalDateTime.now()))
                .setMaterialType("制程报废").setScrapType("QMS_SMASH").setScrapReason(command.getReason().trim())
                .setStatus("1").setAuditor(StrUtil.blankToDefault(command.getOperatorCode(), "QMS"))
                .setAuditTime(LocalDateTime.now()).setAuditVersion(1).setSourceTaskId(task.getId())
                .setApsBatchNo(task.getApsBatchNo()).setSourceType(sourceType)
                .setSourceReference(command.getInspectionTaskId() + ":" + command.getHandleRecordId())
                .setFactoryCode(task.getFactoryCode());
        if (!this.save(entity)) throw new CheckedException("QMS报废同步落账失败");
        if (quantitySourceTask.getPlanNo() != null || quantitySourceTask.getApsBatchNo() != null) {
            sourceQuantityService.record(quantitySourceTask, requestId, MesSourceQuantityKind.SCRAP, command.getQuantity(),
                    entity.getSourceReference(), "QMS【" + command.getInspectionType() + "】报废处置【"
                            + command.getHandleRecordId() + "】，数量【" + command.getQuantity() + "】，原因【"
                            + command.getReason() + "】；执行工单【" + task.getCode() + "】");
        }
    }

    @Override
    public void export(MesScrapMaterialEntity query, HttpServletResponse response) {
//        try {
//
//            // 添加额外的查询条件
//            queryWrapper.lambda()
//                    .like(StrUtil.isNotBlank(query.getMatCode()), MesScrapMaterialEntity::getMatCode, query.getMatCode())
//                    .like(StrUtil.isNotBlank(query.getMatName()), MesScrapMaterialEntity::getMatName, query.getMatName())
//                    .like(StrUtil.isNotBlank(query.getScrapCode()), MesScrapMaterialEntity::getScrapCode, query.getScrapCode())
//                    .like(StrUtil.isNotBlank(query.getScrapName()), MesScrapMaterialEntity::getScrapName, query.getScrapName())
//                    .like(StrUtil.isNotBlank(query.getOperator()), MesScrapMaterialEntity::getOperator, query.getOperator())
//                    .like(StrUtil.isNotBlank(query.getWorkOrder()), MesScrapMaterialEntity::getWorkOrder, query.getWorkOrder())
//                    .eq(StrUtil.isNotBlank(query.getShift()), MesScrapMaterialEntity::getShift, query.getShift())
//                    .eq(StrUtil.isNotBlank(query.getMaterialType()), MesScrapMaterialEntity::getMaterialType, query.getMaterialType())
//                    .eq(StrUtil.isNotBlank(query.getStatus()), MesScrapMaterialEntity::getStatus, query.getStatus())
//                    .orderByDesc(MesScrapMaterialEntity::getCreateTime);
//
//
//            List<MesScrapMaterial> list = this.list(queryWrapper);
//
//            ExcelUtil.exportExcel(list, MesScrapMaterial.class, "报废品管理", response);
//        } catch (Exception e) {
//            throw new CheckedException("导出失败：" + e.getMessage());
//        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String importData(MultipartFile file) {
//        try {
//            List<MesScrapMaterial> list = ExcelUtil.importExcel(file.getInputStream(), MesScrapMaterial.class);
//
//            if (CollUtil.isEmpty(list)) {
//                throw new CheckedException("导入数据为空");
//            }
//
//            // 数据校验和处理
//            for (MesScrapMaterial item : list) {
//                // 设置默认值
//                if (item.getScrapTime() == null) {
//                    item.setScrapTime(LocalDateTime.now());
//                }
//                if (StrUtil.isBlank(item.getStatus())) {
//                    item.setStatus("0"); // 默认为待审核
//                }
//                if (item.getOrgId() == null) {
//                    item.setOrgId(SecurityUtils.getUser().getOrgId());
//                }
//            }
//
//            this.saveBatch(list);
//
//            return "导入成功，共导入" + list.size() + "条数据";
//        } catch (Exception e) {
//            throw new CheckedException("导入失败：" + e.getMessage());
//        }
        return null;
    }

    @Override
    public void template(HttpServletResponse response) {
//        try {
//            ExcelUtil.exportTemplate(MesScrapMaterial.class, "报废品管理导入模板", response);
//        } catch (Exception e) {
//            throw new CheckedException("模板下载失败：" + e.getMessage());
//        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void audit(Long id) {
        MesScrapMaterialEntity entity = baseMapper.selectOne(new LambdaQueryWrapper<MesScrapMaterialEntity>()
                .eq(MesScrapMaterialEntity::getId, id).last("FOR UPDATE"));
        if (entity == null) {
            throw new CheckedException("报废品记录不存在");
        }

        if ("1".equals(entity.getStatus())) {
            return;
        }
        MesTaskEntity task = bindSourceTask(entity);
        int auditVersion = Objects.requireNonNullElse(entity.getAuditVersion(), 0) + 1;
        if (task != null) {
            taskMapper.acquireApsBatchTransactionLock("SCRAP_QUANTITY:" + task.getCode());
            BigDecimal approved = approvedScrap(task.getId());
            if (task.getNoticeNum() == null || approved.add(entity.getQuantity()).compareTo(task.getNoticeNum()) > 0) {
                throw new CheckedException("该工单累计审批报废数量超过工单数量");
            }
            if (task.getPlanNo() != null || task.getApsBatchNo() != null) {
                sourceQuantityService.record(task, "SCRAP-AUDIT:" + id + ":" + auditVersion,
                        MesSourceQuantityKind.SCRAP, entity.getQuantity(), String.valueOf(id),
                        "制程报废记录【" + id + "】审批通过，数量【" + entity.getQuantity() + "】，原因【"
                                + entity.getScrapReason() + "】");
            }
        }
        entity.setStatus("1");
        entity.setAuditVersion(auditVersion);
        entity.setAuditor(SecurityUtils.getUser().getUsername());
        entity.setAuditTime(LocalDateTime.now());

        this.updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelAudit(Long id) {
        MesScrapMaterialEntity entity = baseMapper.selectOne(new LambdaQueryWrapper<MesScrapMaterialEntity>()
                .eq(MesScrapMaterialEntity::getId, id).last("FOR UPDATE"));
        if (entity == null) {
            throw new CheckedException("报废品记录不存在");
        }

        if ("0".equals(entity.getStatus())) {
            if (Objects.requireNonNullElse(entity.getAuditVersion(), 0) > 0) return;
            throw new CheckedException("该记录尚未审核");
        }
        if (StrUtil.startWithIgnoreCase(entity.getSourceType(), "QMS_")) {
            throw new CheckedException("QMS来源报废是已成立的权威事实，MES不得撤销；如需纠正，应由QMS后续形成新的补偿事实");
        }
        MesTaskEntity task = bindSourceTask(entity);
        int auditVersion = Objects.requireNonNullElse(entity.getAuditVersion(), 0);
        if (task != null) {
            if (task.getPlanNo() != null || task.getApsBatchNo() != null) {
                sourceQuantityService.record(task, "SCRAP-CANCEL:" + id + ":" + auditVersion,
                        MesSourceQuantityKind.SCRAP, entity.getQuantity().negate(), String.valueOf(id),
                        "制程报废记录【" + id + "】撤销第【" + auditVersion + "】次审批，冲回数量【"
                                + entity.getQuantity() + "】");
            }
        }
        entity.setStatus("0");
        entity.setAuditor(null);
        entity.setAuditTime(null);

        this.updateById(entity);
    }

    private void validateDraft(MesScrapMaterialEntity entity) {
        if (entity == null || StrUtil.isBlank(entity.getMatCode()) || entity.getQuantity() == null
                || entity.getQuantity().signum() <= 0 || StrUtil.isBlank(entity.getMaterialType())
                || StrUtil.isBlank(entity.getScrapType()) || StrUtil.isBlank(entity.getScrapReason())) {
            throw new CheckedException("报废物料、正数数量、物料类型、废品类型和报废原因不能为空");
        }
        if (!List.of("原料报废", "制程报废").contains(entity.getMaterialType())) {
            throw new CheckedException("物料类型仅支持原料报废或制程报废");
        }
        if ("制程报废".equals(entity.getMaterialType()) && StrUtil.isBlank(entity.getWorkOrder())) {
            throw new CheckedException("制程报废必须关联生产工单");
        }
    }

    private MesTaskEntity bindSourceTask(MesScrapMaterialEntity entity) {
        if (!"制程报废".equals(entity.getMaterialType())) {
            entity.setSourceTaskId(null).setApsBatchNo(null);
            return null;
        }
        MesTaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getCode, StrUtil.trim(entity.getWorkOrder())));
        if (task == null) throw new CheckedException("制程报废关联的生产工单不存在");
        if (!Objects.equals(task.getMatCode(), entity.getMatCode())) {
            throw new CheckedException("报废物料与生产工单产品不一致");
        }
        entity.setWorkOrder(task.getCode()).setSourceTaskId(task.getId()).setApsBatchNo(task.getApsBatchNo());
        return task;
    }

    private BigDecimal approvedScrap(Long taskId) {
        return this.list(new LambdaQueryWrapper<MesScrapMaterialEntity>()
                        .eq(MesScrapMaterialEntity::getSourceTaskId, taskId)
                        .eq(MesScrapMaterialEntity::getStatus, "1"))
                .stream().map(MesScrapMaterialEntity::getQuantity).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String fingerprint(MesScrapMaterialEntity entity) {
        try {
            String raw = String.join("|", String.valueOf(entity.getMatCode()), String.valueOf(entity.getQuantity()),
                    String.valueOf(entity.getWorkOrder()), String.valueOf(entity.getMaterialType()),
                    String.valueOf(entity.getScrapType()), String.valueOf(entity.getScrapReason()),
                    String.valueOf(entity.getScrapLocation()), String.valueOf(entity.getScrapTime()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("报废记录指纹生成失败");
        }
    }

    private String qualityFingerprint(MesQualityDispositionDTO command) {
        try {
            String raw = String.join("|", command.getDispositionKind(), String.valueOf(command.getInspectionType()),
                    String.valueOf(command.getInspectionTaskId()), String.valueOf(command.getHandleRecordId()),
                    command.getSourceTaskCode().trim(), command.getMatCode(),
                    command.getQuantity().stripTrailingZeros().toPlainString(), command.getReason().trim());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("QMS报废同步指纹生成失败");
        }
    }
}

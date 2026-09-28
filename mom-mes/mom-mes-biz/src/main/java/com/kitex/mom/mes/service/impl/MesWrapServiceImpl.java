package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.barcode.api.feign.RemoteBarcodeService;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mes.dto.MesWrapRepackCommand;
import com.kitex.mom.mes.dto.MesWrapUnpackCommand;
import com.kitex.mom.mes.entity.MesWrapEntity;
import com.kitex.mom.mes.entity.MesWrapOperationEntity;
import com.kitex.mom.mes.entity.MesWrapRecordEntity;
import com.kitex.mom.mes.entity.MesWrapRuleEntity;
import com.kitex.mom.mes.enums.MesWrapOperationTypeEnum;
import com.kitex.mom.mes.enums.MesWrapRecordStatusEnum;
import com.kitex.mom.mes.enums.MesWrapStatusEnum;
import com.kitex.mom.mes.mapper.MesWrapMapper;
import com.kitex.mom.mes.mapper.MesWrapOperationMapper;
import com.kitex.mom.mes.mapper.MesWrapRecordMapper;
import com.kitex.mom.mes.service.MesWrapRuleService;
import com.kitex.mom.mes.service.MesWrapService;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 箱码管理
 *
 * @author kitex
 * @since 2025-03-25
 */
@Service
@AllArgsConstructor
public class MesWrapServiceImpl extends ServiceImpl<MesWrapMapper, MesWrapEntity> implements MesWrapService {

    private final RemoteBarcodeService remoteBarcodeService;
    private final MesWrapRuleService ruleService;
    private final RemoteSeqConfigService seqConfigService;
    private final MesWrapRecordMapper wrapRecordMapper;
    private final MesWrapOperationMapper wrapOperationMapper;

    /**
     * 生成箱码
     *
     * @param mesWrap
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @SneakyThrows
    public String addData(MesWrapEntity mesWrap) {
        String mesWrapCode;
        MesWrapRuleEntity rule = ruleService.getOne(new LambdaQueryWrapper<MesWrapRuleEntity>()
                .eq(MesWrapRuleEntity::getWrapCode, mesWrap.getRuleCode())
                .eq(MesWrapRuleEntity::getStatus, Boolean.TRUE)
                .orderByDesc(MesWrapRuleEntity::getCreateTime)
                .last(" limit 1"));
        if (rule == null || rule.getCapacity() == null || rule.getCapacity() <= 0) {
            throw new IllegalStateException("未找到有效包装规则或包装容量无效：" + mesWrap.getRuleCode());
        }
        if (mesWrap.getWrapCapacity() != null && !mesWrap.getWrapCapacity().equals(rule.getCapacity())) {
            throw new IllegalStateException("报工包装容量与当前启用包装规则不一致，请刷新任务后重试");
        }
        if (StrUtil.isNotEmpty(mesWrap.getTaskCode())) {
            JSONObject object = new JSONObject();
            object.set("boxCode", mesWrap.getTaskCode().length() > 2
                    ? mesWrap.getTaskCode().substring(2) : mesWrap.getTaskCode());
            object.append("capacity", rule.getCapacity());
            mesWrapCode = remoteBarcodeService.genBarcode(object, rule.getWrapCode()).getData();
        } else {
            mesWrapCode = seqConfigService.create("mes_wrap").getData();
        }
        if (StrUtil.isBlank(mesWrapCode)) {
            throw new IllegalStateException("箱码生成服务未返回有效箱码");
        }
        mesWrap.setWrapCode(mesWrapCode);
        mesWrap.setWrapCapacity(rule.getCapacity());
        if (StrUtil.isBlank(mesWrap.getStatus())) {
            mesWrap.setStatus(MesWrapStatusEnum.OPEN.name());
        }
        if (mesWrap.getCurrentQuantity() == null) {
            mesWrap.setCurrentQuantity(BigDecimal.ZERO);
        }
        this.save(mesWrap);
        return mesWrapCode;
    }

    /**
     * 分页查询
     *
     * @param pageParam
     * @param query
     * @return
     */
    @Override
    public IPage<MesWrapEntity> queryPage(Page<MesWrapEntity> pageParam, MesWrapEntity query) {
        return baseMapper.queryPage(pageParam, query);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unpack(MesWrapUnpackCommand command) {
        command.setRequestId(command.getRequestId().trim());
        command.setWrapCode(command.getWrapCode().trim());
        command.setReason(command.getReason().trim());
        MesWrapOperationEntity existing = findOperation(command.getRequestId());
        if (existing != null) {
            if (!MesWrapOperationTypeEnum.UNPACK.name().equals(existing.getOperationType())
                    || !command.getWrapCode().equals(existing.getSourceWrapCodes())) {
                throw new IllegalStateException("请求号已用于其他包装操作");
            }
            return;
        }

        MesWrapEntity wrap = lockWrap(command.getWrapCode());
        if (wrap == null) {
            throw new IllegalStateException("箱码不存在：" + command.getWrapCode());
        }
        if (MesWrapStatusEnum.UNPACKED.name().equals(wrap.getStatus())) {
            throw new IllegalStateException("该箱码已拆箱，请勿重复操作");
        }
        if (MesWrapStatusEnum.VOID.name().equals(wrap.getStatus())) {
            throw new IllegalStateException("作废箱码不能拆箱");
        }

        List<MesWrapRecordEntity> packedRecords = wrapRecordMapper.selectList(
                new LambdaQueryWrapper<MesWrapRecordEntity>()
                        .eq(MesWrapRecordEntity::getWrapCode, command.getWrapCode())
                        .eq(MesWrapRecordEntity::getPackingStatus, MesWrapRecordStatusEnum.PACKED.name())
                        .orderByAsc(MesWrapRecordEntity::getId)
                        .last("FOR UPDATE"));
        if (packedRecords.isEmpty()) {
            throw new IllegalStateException("箱内没有可拆出的有效包装内容");
        }

        BigDecimal unpackQuantity = BigDecimal.ZERO;
        for (MesWrapRecordEntity record : packedRecords) {
            BigDecimal quantity = positive(record.getQuantity());
            unpackQuantity = unpackQuantity.add(quantity);
            record.setPackingStatus(MesWrapRecordStatusEnum.UNPACKED.name());
            record.setAvailableQuantity(quantity);
            record.setOperationRequestId(command.getRequestId());
            wrapRecordMapper.updateById(record);
        }
        wrap.setStatus(MesWrapStatusEnum.UNPACKED.name());
        wrap.setCurrentQuantity(BigDecimal.ZERO);
        wrap.setUnpackTime(LocalDateTime.now());
        updateById(wrap);

        saveOperation(command.getRequestId(), MesWrapOperationTypeEnum.UNPACK.name(),
                command.getWrapCode(), null, unpackQuantity, command.getReason(), packedRecords);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String repack(MesWrapRepackCommand command) {
        command.setRequestId(command.getRequestId().trim());
        command.setTargetWrapCode(StrUtil.trim(command.getTargetWrapCode()));
        command.setReason(command.getReason().trim());
        command.setAllocations(command.getAllocations().stream()
                .sorted(Comparator.comparing(MesWrapRepackCommand.Allocation::getRecordId))
                .toList());
        MesWrapOperationEntity existing = findOperation(command.getRequestId());
        if (existing != null) {
            boolean targetConflict = StrUtil.isNotBlank(command.getTargetWrapCode())
                    && !command.getTargetWrapCode().equals(existing.getTargetWrapCode());
            if (!MesWrapOperationTypeEnum.REPACK.name().equals(existing.getOperationType())
                    || targetConflict
                    || !JSONUtil.toJsonStr(command.getAllocations()).equals(existing.getDetailSnapshot())) {
                throw new IllegalStateException("请求号已用于其他包装操作");
            }
            return existing.getTargetWrapCode();
        }

        List<MesWrapRecordEntity> sources = new ArrayList<>();
        Set<String> sourceWrapCodes = new LinkedHashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        MesWrapRecordEntity template = null;
        Set<Long> requestedRecordIds = new LinkedHashSet<>();
        for (MesWrapRepackCommand.Allocation allocation : command.getAllocations()) {
            if (!requestedRecordIds.add(allocation.getRecordId())) {
                throw new IllegalStateException("同一来源记录不能重复分配");
            }
            MesWrapRecordEntity source = wrapRecordMapper.selectOne(
                    new LambdaQueryWrapper<MesWrapRecordEntity>()
                            .eq(MesWrapRecordEntity::getId, allocation.getRecordId())
                            .last("FOR UPDATE"));
            if (source == null) {
                throw new IllegalStateException("包装内容记录不存在：" + allocation.getRecordId());
            }
            if (!MesWrapRecordStatusEnum.UNPACKED.name().equals(source.getPackingStatus())
                    && !MesWrapRecordStatusEnum.PARTIAL.name().equals(source.getPackingStatus())) {
                throw new IllegalStateException("包装内容不处于可重组状态：" + source.getId());
            }
            BigDecimal available = positive(source.getAvailableQuantity());
            if (allocation.getQuantity().compareTo(available) > 0) {
                throw new IllegalStateException("重组数量超过可用数量，记录：" + source.getId());
            }
            if (template != null && (!same(template.getTaskCode(), source.getTaskCode())
                    || !same(template.getProcedureTaskCode(), source.getProcedureTaskCode())
                    || !same(template.getArrangeCode(), source.getArrangeCode()))) {
                throw new IllegalStateException("不同工单、工序任务或派工单的内容不能混箱");
            }
            template = template == null ? source : template;
            sources.add(source);
            if (StrUtil.isNotBlank(source.getWrapCode())) {
                sourceWrapCodes.add(source.getWrapCode());
            }
            total = total.add(allocation.getQuantity());
        }
        if (template == null || total.signum() <= 0) {
            throw new IllegalStateException("重组数量必须大于零");
        }

        MesWrapEntity target = resolveTarget(command.getTargetWrapCode(), template);
        BigDecimal current = positive(target.getCurrentQuantity());
        if (target.getWrapCapacity() == null || target.getWrapCapacity() <= 0) {
            throw new IllegalStateException("目标包装容量无效，不能执行重组");
        }
        if (current.add(total).compareTo(BigDecimal.valueOf(target.getWrapCapacity())) > 0) {
            throw new IllegalStateException("重组后数量超过目标包装容量");
        }
        if (!same(target.getTaskCode(), template.getTaskCode())
                || !same(target.getProcedureTaskCode(), template.getProcedureTaskCode())
                || !same(target.getArrangeCode(), template.getArrangeCode())) {
            throw new IllegalStateException("目标箱码与待重组内容的生产任务不一致");
        }

        for (int i = 0; i < sources.size(); i++) {
            MesWrapRecordEntity source = sources.get(i);
            BigDecimal moved = command.getAllocations().get(i).getQuantity();
            BigDecimal remaining = positive(source.getAvailableQuantity()).subtract(moved);
            source.setAvailableQuantity(remaining);
            source.setPackingStatus(remaining.signum() == 0
                    ? MesWrapRecordStatusEnum.MOVED.name() : MesWrapRecordStatusEnum.PARTIAL.name());
            source.setOperationRequestId(command.getRequestId());
            wrapRecordMapper.updateById(source);

            MesWrapRecordEntity child = new MesWrapRecordEntity()
                    .setReportId(source.getReportId())
                    .setWrapCode(target.getWrapCode())
                    .setTaskCode(source.getTaskCode())
                    .setProcedureTaskCode(source.getProcedureTaskCode())
                    .setArrangeCode(source.getArrangeCode())
                    .setTransferCode(source.getTransferCode())
                    .setQuantity(moved)
                    .setAvailableQuantity(BigDecimal.ZERO)
                    .setPackingStatus(MesWrapRecordStatusEnum.PACKED.name())
                    .setSourceRecordId(source.getId())
                    .setOperationRequestId(command.getRequestId())
                    .setFactoryCode(source.getFactoryCode());
            wrapRecordMapper.insert(child);
        }

        BigDecimal targetQuantity = current.add(total);
        target.setCurrentQuantity(targetQuantity);
        target.setStatus(targetQuantity.compareTo(BigDecimal.valueOf(target.getWrapCapacity())) == 0
                ? MesWrapStatusEnum.SEALED.name() : MesWrapStatusEnum.OPEN.name());
        target.setSourceMode("REPACK");
        target.setRepackTime(LocalDateTime.now());
        updateById(target);

        saveOperation(command.getRequestId(), MesWrapOperationTypeEnum.REPACK.name(),
                String.join(",", sourceWrapCodes), target.getWrapCode(), total, command.getReason(), command.getAllocations());
        return target.getWrapCode();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void registerQuantityPackage(MesWrapEntity wrap, Long reportId, String transferCode, BigDecimal quantity) {
        if (reportId == null || quantity == null || quantity.signum() <= 0) {
            throw new IllegalStateException("包装报工记录和数量不能为空");
        }
        wrap.setWrapCode(StrUtil.trim(wrap.getWrapCode()));
        if (StrUtil.isBlank(wrap.getWrapCode())) {
            throw new IllegalStateException("包装箱码不能为空");
        }
        MesWrapEntity existing = lockWrap(wrap.getWrapCode());
        if (existing != null) {
            throw new IllegalStateException("该箱码已被使用：" + wrap.getWrapCode());
        }
        wrap.setCurrentQuantity(quantity);
        wrap.setStatus(MesWrapStatusEnum.SEALED.name());
        wrap.setSourceMode("PACKAGE_QUANTITY");
        if (wrap.getWrapCapacity() == null) {
            wrap.setWrapCapacity(quantity.setScale(0, java.math.RoundingMode.CEILING).intValueExact());
        }
        if (quantity.compareTo(BigDecimal.valueOf(wrap.getWrapCapacity())) > 0) {
            throw new IllegalStateException("报工数量超过包装容量");
        }
        save(wrap);
        MesWrapRecordEntity record = new MesWrapRecordEntity()
                .setReportId(reportId)
                .setWrapCode(wrap.getWrapCode())
                .setTaskCode(wrap.getTaskCode())
                .setProcedureTaskCode(wrap.getProcedureTaskCode())
                .setArrangeCode(wrap.getArrangeCode())
                .setTransferCode(transferCode)
                .setQuantity(quantity)
                .setAvailableQuantity(BigDecimal.ZERO)
                .setPackingStatus(MesWrapRecordStatusEnum.PACKED.name())
                .setFactoryCode(wrap.getFactoryCode());
        wrapRecordMapper.insert(record);
    }

    private MesWrapOperationEntity findOperation(String requestId) {
        return wrapOperationMapper.selectOne(new LambdaQueryWrapper<MesWrapOperationEntity>()
                .eq(MesWrapOperationEntity::getRequestId, requestId)
                .last("limit 1"));
    }

    private MesWrapEntity lockWrap(String wrapCode) {
        return baseMapper.selectOne(new LambdaQueryWrapper<MesWrapEntity>()
                .eq(MesWrapEntity::getWrapCode, wrapCode)
                .last("FOR UPDATE"));
    }

    private MesWrapEntity resolveTarget(String targetWrapCode, MesWrapRecordEntity template) {
        if (StrUtil.isNotBlank(targetWrapCode)) {
            MesWrapEntity target = lockWrap(targetWrapCode);
            if (target == null) {
                throw new IllegalStateException("目标箱码不存在：" + targetWrapCode);
            }
            if (!MesWrapStatusEnum.OPEN.name().equals(target.getStatus())
                    && !MesWrapStatusEnum.UNPACKED.name().equals(target.getStatus())) {
                throw new IllegalStateException("目标箱码不是可装箱状态");
            }
            return target;
        }
        MesWrapEntity sourceWrap = lockWrap(template.getWrapCode());
        if (sourceWrap == null || StrUtil.isBlank(sourceWrap.getRuleCode())) {
            throw new IllegalStateException("来源包装缺少有效包装规则，无法自动生成目标箱码");
        }
        MesWrapEntity target = new MesWrapEntity()
                .setRuleCode(sourceWrap.getRuleCode())
                .setTaskCode(template.getTaskCode())
                .setProcedureTaskCode(template.getProcedureTaskCode())
                .setArrangeCode(template.getArrangeCode())
                .setWrapType(sourceWrap.getWrapType())
                .setSourceMode("REPACK")
                .setFactoryCode(sourceWrap.getFactoryCode());
        addData(target);
        return target;
    }

    private void saveOperation(String requestId, String operationType, String sourceWrapCodes,
                               String targetWrapCode, BigDecimal quantity, String reason, Object snapshot) {
        MomUser user = SecurityUtils.getUser();
        MesWrapOperationEntity operation = new MesWrapOperationEntity()
                .setRequestId(requestId)
                .setOperationType(operationType)
                .setSourceWrapCodes(sourceWrapCodes)
                .setTargetWrapCode(targetWrapCode)
                .setQuantity(quantity)
                .setReason(reason)
                .setDetailSnapshot(JSONUtil.toJsonStr(snapshot))
                .setOperatorCode(user == null ? "SYSTEM" : user.getUsername())
                .setOperatorName(user == null ? "系统" : user.getName())
                .setOperationTime(LocalDateTime.now());
        wrapOperationMapper.insert(operation);
    }

    private BigDecimal positive(BigDecimal quantity) {
        if (quantity != null && quantity.signum() < 0) {
            throw new IllegalStateException("包装数量账存在负数，请先完成数据复核");
        }
        return quantity == null ? BigDecimal.ZERO : quantity;
    }

    private boolean same(String left, String right) {
        return StrUtil.equals(left, right);
    }
}

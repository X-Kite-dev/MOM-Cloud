package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.mdm.constant.AvailableStatusEnum;
import com.kitex.mom.mdm.entity.MdmFactoryModelEntity;
import com.kitex.mom.mdm.entity.MdmStationSettingEntity;
import com.kitex.mom.mdm.mapper.MdmStationSettingMapper;
import com.kitex.mom.mdm.service.MdmFactoryModelService;
import com.kitex.mom.mdm.service.MdmStationSettingService;
import com.kitex.mom.mdm.vo.MdmFactoryModelAncestorVO;
import com.kitex.mom.mdm.vo.excel.MdmProcedureStationBindExcelVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工序-工位绑定 Service 实现类
 *
 * @author claude
 * @date 2025-07-30
 */
@Service
@RequiredArgsConstructor
public class MdmStationSettingServiceImpl extends ServiceImpl<MdmStationSettingMapper, MdmStationSettingEntity> implements MdmStationSettingService {

    private final MdmFactoryModelService mdmFactoryModelService;

    /**
     * 分页查询工序-工位绑定
     *
     * @param page 分页对象
     * @param bind 查询条件
     * @return 分页结果
     */
    @Override
    public Page<MdmStationSettingEntity> getPage(Page page, MdmStationSettingEntity bind) {
        LambdaQueryWrapper<MdmStationSettingEntity> wrapper = new LambdaQueryWrapper<>();

        wrapper.like(StrUtil.isNotBlank(bind.getProcedureCode()), MdmStationSettingEntity::getProcedureCode, bind.getProcedureCode())
                .like(StrUtil.isNotBlank(bind.getProcedureName()), MdmStationSettingEntity::getProcedureName, bind.getProcedureName())
                .eq(Objects.nonNull(bind.getStatus()), MdmStationSettingEntity::getStatus, bind.getStatus());
        if (bind.getStationId() != null) {
            List<Long> collect = mdmFactoryModelService.getAllChildren(bind.getStationId())
                    .stream().map(MdmFactoryModelEntity::getId).collect(Collectors.toList());
            collect.add(bind.getStationId());
            wrapper.in(MdmStationSettingEntity::getStationId, collect);
        }
        wrapper.orderByDesc(MdmStationSettingEntity::getProcedureCode).orderByDesc(MdmStationSettingEntity::getCreateTime);
        return this.page(page, wrapper);
    }

    /**
     * 查询工序-工位绑定状态的数量
     *
     * @return
     */
    @Override
    public Map<Boolean, Long> getCount(MdmStationSettingEntity bind) {
        LambdaQueryWrapper<MdmStationSettingEntity> wrapper = new LambdaQueryWrapper<>();

        wrapper.like(StrUtil.isNotBlank(bind.getProcedureCode()), MdmStationSettingEntity::getProcedureCode, bind.getProcedureCode())
                .like(StrUtil.isNotBlank(bind.getProcedureName()), MdmStationSettingEntity::getProcedureName, bind.getProcedureName());
        if (bind.getStationId() != null) {
            List<Long> collect = mdmFactoryModelService.getAllChildren(bind.getStationId())
                    .stream().map(MdmFactoryModelEntity::getId).collect(Collectors.toList());
            collect.add(bind.getStationId());
            wrapper.in(MdmStationSettingEntity::getStationId, collect);
        }
        return this.list(wrapper)
                .stream()
                .collect(Collectors.groupingBy(MdmStationSettingEntity::getStatus, Collectors.counting()));
    }

    @Override
    public List<MdmStationSettingEntity> getByLineId(Long lineId) {
        return baseMapper.selectByLineId(lineId);
    }

    @Override
    public List<MdmStationSettingEntity> getStationsByProcedure(String procedureCode) {
        return baseMapper.selectStationsByProcedure(procedureCode);
    }

    @Override
    public List<MdmStationSettingEntity> getProceduresByStation(Long stationId) {
        return baseMapper.selectProceduresByStation(stationId);
    }

    @Override
    public List<MdmStationSettingEntity> getByWorkshopId(Long workshopId) {
        return baseMapper.selectByWorkshopId(workshopId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean batchBind(List<MdmStationSettingEntity> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return true;
        }

        // 校验：所有记录的工序编码需一致，且批次内不得出现相同工位
        String procedureCode = bindings.get(0).getProcedureCode();
        Set<Long> stationIdSet = new HashSet<>();
        List<String> errorMessages = new ArrayList<>();
        for (MdmStationSettingEntity binding : bindings) {
            if (!stationIdSet.add(binding.getStationId())) {
                // 批次内重复的 工序-工位 视为已存在绑定
                String name = binding.getStationName() == null ? String.valueOf(binding.getStationId()) : binding.getStationName();
                errorMessages.add(name + ":该工位与当前工序的绑定关系已存在");
                continue;
            }
            // 校验：该工位是否已绑定其他工序（排除当前批次对应的工序）
            if (this.count(new LambdaQueryWrapper<MdmStationSettingEntity>()
                    .eq(MdmStationSettingEntity::getStationId, binding.getStationId())
                    .eq(MdmStationSettingEntity::getStatus, true)
                    .ne(MdmStationSettingEntity::getProcedureCode, procedureCode)) > 0) {
                String name = binding.getStationName() == null ? String.valueOf(binding.getStationId()) : binding.getStationName();
                errorMessages.add(name + ":该工位已绑定其他工序");
            }
        }
        if (!errorMessages.isEmpty()) {
            throw new CheckedException(String.join("; ", errorMessages));
        }

        // 设置状态
        bindings.forEach(binding -> {
            normalizeCapacity(binding);
            binding.setId(null);
            binding.setStatus(true);
        });
        validateResourceGroupConsistency(bindings, procedureCode, null);

        this.remove(new LambdaQueryWrapper<MdmStationSettingEntity>()
                .eq(MdmStationSettingEntity::getProcedureCode, bindings.get(0).getProcedureCode()));

        return saveBatch(bindings);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean unbind(String procedureCode, Long stationId) {
        LambdaQueryWrapper<MdmStationSettingEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmStationSettingEntity::getProcedureCode, procedureCode)
                .eq(MdmStationSettingEntity::getStationId, stationId);

        return remove(wrapper);
    }

    /**
     * 新增工序-工位绑定
     *
     * @param bind 绑定信息
     * @return 操作结果
     */
    @Override
    public Boolean saveProcedureStationBind(MdmStationSettingEntity bind) {
        normalizeCapacity(bind);
        validateResourceGroupConsistency(List.of(bind), null, null);
        // 检查是否已存在绑定
        if (this.isBindExists(bind.getProcedureCode(), bind.getStationId(), null)) {
            throw new CheckedException("该工序和工位的绑定关系已存在");
        }
        // 检查是否已存在绑定
        if (this.isBindExists(null, bind.getStationId(), null)) {
            throw new CheckedException("该工位已绑定其他工序");
        }
        List<MdmFactoryModelAncestorVO> stationByAllParent = mdmFactoryModelService.getStationByAllParent(new Long[]{bind.getStationId()});
        if (stationByAllParent == null || stationByAllParent.isEmpty()) {
            throw new CheckedException("工位不存在");
        }
        // 上级信息处理
        bindHandler(stationByAllParent.get(0), bind);
        bind.setStatus(Boolean.TRUE);
        return this.save(bind);
    }

    /**
     * 更新工序-工位绑定信息
     *
     * @param bind 绑定信息
     * @return 是否成功
     */
    @Override
    public Boolean updateProcedureStationBind(MdmStationSettingEntity bind) {
        normalizeCapacity(bind);
        validateResourceGroupConsistency(List.of(bind), null, bind.getId());
        // 检查是否已存在绑定
        if (this.isBindExists(null, bind.getStationId(), bind.getId())) {
            throw new CheckedException("该工位已绑定其他工序");
        }
        List<MdmFactoryModelAncestorVO> stationByAllParent = mdmFactoryModelService.getStationByAllParent(new Long[]{bind.getStationId()});
        if (stationByAllParent == null || stationByAllParent.isEmpty()) {
            throw new CheckedException("工位不存在");
        }
        // 上级信息处理
        bindHandler(stationByAllParent.get(0), bind);
        return this.updateById(bind);
    }

    private void normalizeCapacity(MdmStationSettingEntity bind) {
        if (bind == null) {
            throw new CheckedException("工序工位配置不能为空");
        }
        int parallelCapacity = Optional.ofNullable(bind.getParallelCapacity()).orElse(1);
        if (parallelCapacity < 1 || parallelCapacity > 100) {
            throw new CheckedException("工位并行槽位数必须为1至100");
        }
        if (bind.getStationCapacity() != null && bind.getStationCapacity().signum() <= 0) {
            throw new CheckedException("工位产能必须大于0");
        }
        if (bind.getStandardWorkingTime() != null && bind.getStandardWorkingTime().signum() <= 0) {
            throw new CheckedException("标准工时必须大于0");
        }
        bind.setParallelCapacity(parallelCapacity);
        bind.setPriority(Optional.ofNullable(bind.getPriority()).orElse(1000));
        bind.setIsPrimary(Boolean.TRUE.equals(bind.getIsPrimary()));
        if (StrUtil.isBlank(bind.getResourceGroupCode())) {
            bind.setResourceGroupCode(null);
            bind.setResourceGroupName(null);
            bind.setResourceGroupCapacity(null);
            return;
        }
        bind.setResourceGroupCode(bind.getResourceGroupCode().trim());
        if (StrUtil.isBlank(bind.getResourceGroupName())) {
            throw new CheckedException("配置共享资源组时资源组名称不能为空");
        }
        bind.setResourceGroupName(bind.getResourceGroupName().trim());
        int groupCapacity = Optional.ofNullable(bind.getResourceGroupCapacity()).orElse(1);
        if (groupCapacity < 1 || groupCapacity > 100) {
            throw new CheckedException("共享资源组并行容量必须为1至100");
        }
        bind.setResourceGroupCapacity(groupCapacity);
    }

    /**
     * 同一租户内相同资源组编码必须保持名称和共享容量一致，避免跨工厂排程时得到相互矛盾的能力。
     */
    private void validateResourceGroupConsistency(List<MdmStationSettingEntity> bindings,
                                                  String replacingProcedureCode, Long excludeId) {
        Map<String, MdmStationSettingEntity> definitions = new LinkedHashMap<>();
        for (MdmStationSettingEntity binding : bindings) {
            if (StrUtil.isBlank(binding.getResourceGroupCode())) {
                continue;
            }
            MdmStationSettingEntity previous = definitions.putIfAbsent(binding.getResourceGroupCode(), binding);
            if (previous != null && (!Objects.equals(previous.getResourceGroupName(), binding.getResourceGroupName())
                    || !Objects.equals(previous.getResourceGroupCapacity(), binding.getResourceGroupCapacity()))) {
                throw new CheckedException("同一批次中资源组[" + binding.getResourceGroupCode() + "]的名称或容量不一致");
            }
        }
        for (MdmStationSettingEntity definition : definitions.values()) {
            List<MdmStationSettingEntity> existing = this.list(new LambdaQueryWrapper<MdmStationSettingEntity>()
                    .eq(MdmStationSettingEntity::getResourceGroupCode, definition.getResourceGroupCode())
                    .eq(MdmStationSettingEntity::getStatus, true)
                    .ne(StrUtil.isNotBlank(replacingProcedureCode), MdmStationSettingEntity::getProcedureCode,
                            replacingProcedureCode)
                    .ne(excludeId != null, MdmStationSettingEntity::getId, excludeId));
            boolean conflict = existing.stream().anyMatch(item ->
                    !Objects.equals(item.getResourceGroupName(), definition.getResourceGroupName())
                            || !Objects.equals(Optional.ofNullable(item.getResourceGroupCapacity()).orElse(1),
                            definition.getResourceGroupCapacity()));
            if (conflict) {
                throw new CheckedException("资源组[" + definition.getResourceGroupCode()
                        + "]已存在不同名称或共享容量，请统一配置后再保存");
            }
        }
    }

    /**
     * 工厂模型处理
     *
     * @param factoryModelAncestorVO 工厂模型信息
     * @param bind                   工序-工位绑定信息
     * @return 工序-工位绑定信息
     */
    private MdmStationSettingEntity bindHandler(MdmFactoryModelAncestorVO factoryModelAncestorVO, MdmStationSettingEntity bind) {
        bind.setLineId(factoryModelAncestorVO.getLineId());
        bind.setLineName(factoryModelAncestorVO.getLineName());
        bind.setLineCode(factoryModelAncestorVO.getLineCode());
        bind.setWorkshopId(factoryModelAncestorVO.getWorkshopId());
        bind.setWorkshopName(factoryModelAncestorVO.getWorkshopName());
        bind.setWorkshopCode(factoryModelAncestorVO.getWorkshopCode());
        bind.setFactoryId(factoryModelAncestorVO.getFactoryId());
        bind.setFactoryName(factoryModelAncestorVO.getFactoryName());
        bind.setFactoryCode(factoryModelAncestorVO.getFactoryCode());
        return bind;
    }

    @Override
    public Map<String, List<MdmStationSettingEntity>> getLineProcessStationMap(Long lineId) {
        List<MdmStationSettingEntity> bindings = getByLineId(lineId);
        return bindings.stream()
                .collect(Collectors.groupingBy(MdmStationSettingEntity::getProcedureCode));
    }

    @Override
    public Map<Long, List<MdmStationSettingEntity>> getLineStationProcessMap(Long lineId) {
        List<MdmStationSettingEntity> bindings = getByLineId(lineId);
        return bindings.stream()
                .collect(Collectors.groupingBy(MdmStationSettingEntity::getStationId));
    }

    @Override
    public boolean isBindExists(String procedureCode, Long stationId, Long excludeId) {
        LambdaQueryWrapper<MdmStationSettingEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(procedureCode != null, MdmStationSettingEntity::getProcedureCode, procedureCode)
                .eq(MdmStationSettingEntity::getStationId, stationId)
                .eq(MdmStationSettingEntity::getStatus, true)
                .ne(excludeId != null, MdmStationSettingEntity::getId, excludeId);

        return count(wrapper) > 0;
    }

    @Override
    public MdmStationSettingEntity getPrimaryProcedureByStation(Long stationId) {
        LambdaQueryWrapper<MdmStationSettingEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MdmStationSettingEntity::getStationId, stationId)
                .eq(MdmStationSettingEntity::getIsPrimary, true)
                .eq(MdmStationSettingEntity::getStatus, true)
                .last("LIMIT 1");

        return getOne(wrapper);
    }

    @Override
    public Map<Long, String> getLineStationPrimaryProcedureMap(Long lineId) {
        List<MdmStationSettingEntity> bindings = getByLineId(lineId);
        return bindings.stream()
                .filter(binding -> Boolean.TRUE.equals(binding.getIsPrimary()))
                .collect(Collectors.toMap(
                        MdmStationSettingEntity::getStationId,
                        MdmStationSettingEntity::getProcedureCode,
                        (existing, replacement) -> existing
                ));
    }

    @Override
    public List<String> getLineProcedureSequence(Long lineId) {
        List<MdmStationSettingEntity> bindings = getByLineId(lineId);
        return bindings.stream()
                .filter(binding -> Boolean.TRUE.equals(binding.getIsPrimary()))
                .sorted((a, b) -> {
                    int priorityCompare = Integer.compare(a.getPriority(), b.getPriority());
                    if (priorityCompare != 0) {
                        return priorityCompare;
                    }
                    return a.getProcedureCode().compareTo(b.getProcedureCode());
                })
                .map(MdmStationSettingEntity::getProcedureCode)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, Long> getLineProcedurePrimaryStationMap(Long lineId) {
        List<MdmStationSettingEntity> bindings = getByLineId(lineId);
        return bindings.stream()
                .filter(binding -> Boolean.TRUE.equals(binding.getIsPrimary()))
                .collect(Collectors.toMap(
                        MdmStationSettingEntity::getProcedureCode,
                        MdmStationSettingEntity::getStationId,
                        (existing, replacement) -> existing
                ));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R importProcedureStationBind(List<MdmProcedureStationBindExcelVO> excelVOList, BindingResult bindingResult) {
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();

        List<MdmStationSettingEntity> insertList = new ArrayList<>();
        Set<String> uniqueKey = new HashSet<>();

        // 现有数据建立唯一索引（工序编码+工位ID）
        this.list().forEach(e -> uniqueKey.add(e.getProcedureCode() + "#" + e.getStationCode()));

        // 工厂模型数据
        Map<String, MdmFactoryModelAncestorVO> factoryModelMap = mdmFactoryModelService.getStationByAllParent(null)
                .stream()
                .collect(Collectors.toMap(MdmFactoryModelAncestorVO::getStationCode, Function.identity()));
        for (MdmProcedureStationBindExcelVO vo : excelVOList) {
            Set<String> errorMsg = new HashSet<>();

            String key = (vo.getProcedureCode() + "#" + vo.getStationCode());
            if (uniqueKey.contains(key)) {
                errorMsg.add("工序和工位已存在");
            }
            MdmFactoryModelAncestorVO mdmFactoryModelAncestorVO = factoryModelMap.get(vo.getStationCode());
            if (mdmFactoryModelAncestorVO == null) {
                errorMsg.add("工位不存在");
            }
            if (CollUtil.isEmpty(errorMsg)) {
                MdmStationSettingEntity e = new MdmStationSettingEntity();
                BeanUtils.copyProperties(vo, e);
                // 上级信息处理
                bindHandler(mdmFactoryModelAncestorVO, e);
                e.setStationId(mdmFactoryModelAncestorVO.getStationId());
                e.setStationName(mdmFactoryModelAncestorVO.getStationName());
                e.setStatus(Boolean.TRUE);
                try {
                    normalizeCapacity(e);
                    validateResourceGroupConsistency(List.of(e), null, null);
                } catch (CheckedException ex) {
                    errorMsg.add(ex.getMessage());
                }
                if (!errorMsg.isEmpty()) {
                    errorMessageList.add(new ErrorMessage(vo.getLineNum() + 1, errorMsg));
                    continue;
                }
                insertList.add(e);
                uniqueKey.add(key);
            } else {
                // 数据不合法情况
                errorMessageList.add(new ErrorMessage(vo.getLineNum() + 1, errorMsg));
            }
        }
        try {
            validateResourceGroupConsistency(insertList, null, null);
        } catch (CheckedException ex) {
            errorMessageList.add(new ErrorMessage(1L, Set.of(ex.getMessage())));
            return R.failed(errorMessageList);
        }
        this.saveBatch(insertList);
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
        return R.ok(null, "工序-工位绑定导入成功");
    }

    @Override
    public List<MdmProcedureStationBindExcelVO> export(MdmStationSettingEntity query, Long[] ids) {
        return this.list(Wrappers.lambdaQuery(query).in(ArrayUtil.isNotEmpty(ids), MdmStationSettingEntity::getId, ids))
                .stream()
                .map(mdmProcedureStationBind -> {
                    MdmProcedureStationBindExcelVO mdmProcedureStationBindExcelVO = new MdmProcedureStationBindExcelVO();
                    BeanUtils.copyProperties(mdmProcedureStationBind, mdmProcedureStationBindExcelVO);
                    mdmProcedureStationBindExcelVO.setStatus(AvailableStatusEnum.labelOf(mdmProcedureStationBind.getStatus()));
                    return mdmProcedureStationBindExcelVO;
                })
                .collect(Collectors.toList());
    }

    /**
     * 工位-员工绑定
     *
     * @param bind 绑定信息
     * @return 操作结果
     */
    @Override
    public Boolean employeeBind(MdmStationSettingEntity bind) {
        return this.updateById(bind);
    }

    /**
     * 设备-员工绑定
     *
     * @param bind 绑定信息
     * @return 操作结果
     */
    @Override
    public Boolean deviceBind(MdmStationSettingEntity bind) {
        return this.updateById(bind);
    }
}

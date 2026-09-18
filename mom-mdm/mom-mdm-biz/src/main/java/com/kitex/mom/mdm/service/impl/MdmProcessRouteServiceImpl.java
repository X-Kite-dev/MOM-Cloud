package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.mdm.dto.*;
import com.kitex.mom.mdm.entity.*;
import com.kitex.mom.mdm.mapper.*;
import com.kitex.mom.mdm.service.MdmProcessRouteService;
import com.kitex.mom.mdm.vo.*;
import com.kitex.mom.mdm.vo.excel.MdmProcessRouteExcelVO;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工艺路线
 *
 * @author kitex
 * @date 2024-10-30 14:36:59
 */
@Service
@AllArgsConstructor
public class MdmProcessRouteServiceImpl extends ServiceImpl<MdmProcessRouteMapper, MdmProcessRouteEntity>
        implements MdmProcessRouteService {

    private final MdmProcessRouteWorkingMapper mdmProcessRouteWorkingMapper;

    private final MdmMaterialMapper mdmMaterialMapper;

    private final MdmBomMapper mdmBomMapper;

    private final MdmProcessProcedureSopMapper mdmProcessProcedureSopMapper;

    private final MdmProcessProcedureMatMapper mdmProcessProcedureMatMapper;

    private final MdmProcessProcedureSettingMapper mdmProcessProcedureSettingMapper;

    private final MdmStationSettingMapper mdmStationSettingMapper;


    /**
     * 通过id删除工艺路线
     *
     * @param ids id列表
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeRoute(List<Long> ids) {
        this.removeBatchByIds(ids);
        return Boolean.TRUE;
    }

    /**
     * 通过id查询工艺路线
     *
     * @param id id
     * @return R
     */
    @Override
    @SneakyThrows
    public MdmProcessRouteDTO getRouteById(Long id) {
        MdmProcessRouteDTO mdmProcessRouteDTO = new MdmProcessRouteDTO();
        MdmProcessRouteEntity mdmProcessRouteEntity = this.getById(id);
        BeanUtils.copyProperties(mdmProcessRouteEntity, mdmProcessRouteDTO);
        if (mdmProcessRouteEntity.getProcedureSnapshot() != null) {
            ObjectMapper mapper = new ObjectMapper();
            mdmProcessRouteDTO.setProcedureSnapshot(mapper.readTree(mdmProcessRouteEntity.getProcedureSnapshot()));
        }
        List<MdmProcessRouteWorkingEntity> mdmProcessRouteWorkingEntities = mdmProcessRouteWorkingMapper.selectList(
                new LambdaQueryWrapper<MdmProcessRouteWorkingEntity>()
                        .eq(MdmProcessRouteWorkingEntity::getRouteCode, mdmProcessRouteEntity.getRouteCode()));
        List<MdmProcessRouteWorkingVO> procedureList = new ArrayList<>();
        for (MdmProcessRouteWorkingEntity mdmProcessRouteWorkingEntity : mdmProcessRouteWorkingEntities) {
            MdmProcessRouteWorkingVO mdmProcessRouteWorkingVO = new MdmProcessRouteWorkingVO();
            BeanUtils.copyProperties(mdmProcessRouteWorkingEntity, mdmProcessRouteWorkingVO);
            mdmProcessRouteWorkingVO.setMdmProcessProcedureSopList(mdmProcessProcedureSopMapper.selectList(
                    new LambdaQueryWrapper<MdmProcessProcedureSopEntity>()
                            .eq(MdmProcessProcedureSopEntity::getRouteCode, mdmProcessRouteEntity.getRouteCode())
                            .eq(MdmProcessProcedureSopEntity::getProcessCode, mdmProcessRouteWorkingEntity.getProcessCode())));
            mdmProcessRouteWorkingVO.setMdmProcessProcedureMatList(mdmProcessProcedureMatMapper.selectList(
                    new LambdaQueryWrapper<MdmProcessProcedureMatEntity>()
                            .eq(MdmProcessProcedureMatEntity::getRouteCode, mdmProcessRouteEntity.getRouteCode())
                            .eq(MdmProcessProcedureMatEntity::getProcessCode, mdmProcessRouteWorkingEntity.getProcessCode())));
            procedureList.add(mdmProcessRouteWorkingVO);
        }
        mdmProcessRouteDTO.setProcedureList(procedureList);
        return mdmProcessRouteDTO;
    }


    /**
     * 通过ids查询工艺路线
     *
     * @param ids ids
     * @return R
     */
    @Override
    @SneakyThrows
    public List<MdmProcessRouteDTO> getRouteByIds(List<Long> ids) {
        List<MdmProcessRouteDTO> result = new ArrayList<>();
        for (Long id : ids) {
            MdmProcessRouteDTO mdmProcessRouteDTO = new MdmProcessRouteDTO();
            MdmProcessRouteEntity mdmProcessRouteEntity = this.getById(id);
            BeanUtils.copyProperties(mdmProcessRouteEntity, mdmProcessRouteDTO);
            if (mdmProcessRouteEntity.getProcedureSnapshot() != null) {
                ObjectMapper mapper = new ObjectMapper();
                mdmProcessRouteDTO.setProcedureSnapshot(mapper.readTree(mdmProcessRouteEntity.getProcedureSnapshot()));
            }
            List<MdmProcessRouteWorkingEntity> mdmProcessRouteWorkingEntities = mdmProcessRouteWorkingMapper.selectList(
                    new LambdaQueryWrapper<MdmProcessRouteWorkingEntity>()
                            .eq(MdmProcessRouteWorkingEntity::getRouteCode, mdmProcessRouteEntity.getRouteCode()));
            List<MdmProcessRouteWorkingVO> procedureList = new ArrayList<>();
            for (MdmProcessRouteWorkingEntity mdmProcessRouteWorkingEntity : mdmProcessRouteWorkingEntities) {
                MdmProcessRouteWorkingVO mdmProcessRouteWorkingVO = new MdmProcessRouteWorkingVO();
                BeanUtils.copyProperties(mdmProcessRouteWorkingEntity, mdmProcessRouteWorkingVO);
                mdmProcessRouteWorkingVO.setMdmProcessProcedureSopList(mdmProcessProcedureSopMapper.selectList(
                        new LambdaQueryWrapper<MdmProcessProcedureSopEntity>()
                                .eq(MdmProcessProcedureSopEntity::getRouteCode, mdmProcessRouteEntity.getRouteCode())
                                .eq(MdmProcessProcedureSopEntity::getProcessCode, mdmProcessRouteWorkingEntity.getProcessCode())));
                mdmProcessRouteWorkingVO.setMdmProcessProcedureMatList(mdmProcessProcedureMatMapper.selectList(
                        new LambdaQueryWrapper<MdmProcessProcedureMatEntity>()
                                .eq(MdmProcessProcedureMatEntity::getRouteCode, mdmProcessRouteEntity.getRouteCode())
                                .eq(MdmProcessProcedureMatEntity::getProcessCode, mdmProcessRouteWorkingEntity.getProcessCode())));

                // 查询工序绑定的默认工作中心列表
                List<MdmStationSettingEntity> stationList = mdmStationSettingMapper.selectList(
                        new LambdaQueryWrapper<MdmStationSettingEntity>()
                                .eq(MdmStationSettingEntity::getProcedureCode, mdmProcessRouteWorkingEntity.getProcessCode())
                                .eq(MdmStationSettingEntity::getStatus, Boolean.TRUE)
                                .eq(MdmStationSettingEntity::getDelFlag, "0")
                );
                List<MdmProcessProcedureWorkVO> workList = stationList.stream()
                        .map(station -> {
                            MdmProcessProcedureWorkVO vo = new MdmProcessProcedureWorkVO();
                            vo.setWorkCode(station.getStationCode());
                            vo.setWorkName(station.getStationName());
                            vo.setLineCode(station.getLineCode());
                            vo.setLineName(station.getLineName());
                            vo.setPrimary(station.getIsPrimary());
                            vo.setPriority(station.getPriority());
                            vo.setStandardWorkingTime(station.getStandardWorkingTime());
                            vo.setStationCapacity(station.getStationCapacity());
                            vo.setParallelCapacity(station.getParallelCapacity());
                            vo.setResourceGroupCode(station.getResourceGroupCode());
                            vo.setResourceGroupName(station.getResourceGroupName());
                            vo.setResourceGroupCapacity(station.getResourceGroupCapacity());
                            vo.setDeviceCodes(station.getDeviceCodes());
                            vo.setDeviceNames(station.getDeviceNames());
                            vo.setEmployeeCodes(station.getEmployeeCodes());
                            vo.setEmployeeNames(station.getEmployeeNames());
                            return vo;
                        }).collect(Collectors.toList());
                mdmProcessRouteWorkingVO.setMdmProcessProcedureWorkList(workList);
                procedureList.add(mdmProcessRouteWorkingVO);
            }
            if (!procedureList.isEmpty()) {
                // 根据 orderNum 进行排序
                procedureList.sort(Comparator.comparingInt(MdmProcessRouteWorkingVO::getOrderNum));
            }
            mdmProcessRouteDTO.setProcedureList(procedureList);
            result.add(mdmProcessRouteDTO);
        }
        return result;
    }

    /**
     * 通过id查询工艺路线
     *
     * @param id id
     * @return R
     */
    @Override
    @SneakyThrows
    public MdmProcessRouteDTO getRouteByIdAndLineCode(Long id, String lineCode) {
        MdmProcessRouteDTO mdmProcessRouteDTO = new MdmProcessRouteDTO();
        MdmProcessRouteEntity mdmProcessRouteEntity = this.getById(id);
        BeanUtils.copyProperties(mdmProcessRouteEntity, mdmProcessRouteDTO);
        if (mdmProcessRouteEntity.getProcedureSnapshot() != null) {
            ObjectMapper mapper = new ObjectMapper();
            mdmProcessRouteDTO.setProcedureSnapshot(mapper.readTree(mdmProcessRouteEntity.getProcedureSnapshot()));
        }
        List<MdmProcessRouteWorkingEntity> mdmProcessRouteWorkingEntities = mdmProcessRouteWorkingMapper.selectList(
                new LambdaQueryWrapper<MdmProcessRouteWorkingEntity>()
                        .eq(MdmProcessRouteWorkingEntity::getRouteCode, mdmProcessRouteEntity.getRouteCode()));
        List<MdmProcessRouteWorkingVO> procedureList = new ArrayList<>();
        for (MdmProcessRouteWorkingEntity mdmProcessRouteWorkingEntity : mdmProcessRouteWorkingEntities) {
            MdmProcessRouteWorkingVO mdmProcessRouteWorkingVO = new MdmProcessRouteWorkingVO();
            BeanUtils.copyProperties(mdmProcessRouteWorkingEntity, mdmProcessRouteWorkingVO);
            mdmProcessRouteWorkingVO.setMdmProcessProcedureSopList(mdmProcessProcedureSopMapper.selectList(
                    new LambdaQueryWrapper<MdmProcessProcedureSopEntity>()
                            .eq(MdmProcessProcedureSopEntity::getRouteCode, mdmProcessRouteEntity.getRouteCode())
                            .eq(MdmProcessProcedureSopEntity::getProcessCode, mdmProcessRouteWorkingEntity.getProcessCode())));
            mdmProcessRouteWorkingVO.setMdmProcessProcedureMatList(mdmProcessProcedureMatMapper.selectList(
                    new LambdaQueryWrapper<MdmProcessProcedureMatEntity>()
                            .eq(MdmProcessProcedureMatEntity::getRouteCode, mdmProcessRouteEntity.getRouteCode())
                            .eq(MdmProcessProcedureMatEntity::getProcessCode, mdmProcessRouteWorkingEntity.getProcessCode())));

            // 需要根据选择的线体，获取绑定工序的工位(工作中心)
            // 查询工序绑定的默认工作中心列表
            List<MdmStationSettingEntity> stationList = mdmStationSettingMapper.selectList(
                    new LambdaQueryWrapper<MdmStationSettingEntity>()
                            .eq(MdmStationSettingEntity::getProcedureCode, mdmProcessRouteWorkingEntity.getProcessCode())
                            .eq(MdmStationSettingEntity::getStatus, Boolean.TRUE)
                            .eq(MdmStationSettingEntity::getDelFlag, "0")
            );
            List<MdmProcessProcedureWorkVO> workList = stationList.stream()
                    .filter(station -> station.getLineCode().equals(lineCode))
                    .map(station -> {
                        MdmProcessProcedureWorkVO vo = new MdmProcessProcedureWorkVO();
                        vo.setWorkCode(station.getStationCode());
                        vo.setWorkName(station.getStationName());
                        vo.setLineCode(station.getLineCode());
                        vo.setLineName(station.getLineName());
                        vo.setPrimary(station.getIsPrimary());
                        vo.setPriority(station.getPriority());
                        vo.setStandardWorkingTime(station.getStandardWorkingTime());
                        vo.setStationCapacity(station.getStationCapacity());
                        vo.setParallelCapacity(station.getParallelCapacity());
                        vo.setResourceGroupCode(station.getResourceGroupCode());
                        vo.setResourceGroupName(station.getResourceGroupName());
                        vo.setResourceGroupCapacity(station.getResourceGroupCapacity());
                        vo.setDeviceCodes(station.getDeviceCodes());
                        vo.setDeviceNames(station.getDeviceNames());
                        vo.setEmployeeCodes(station.getEmployeeCodes());
                        vo.setEmployeeNames(station.getEmployeeNames());
                        return vo;
                    }).collect(Collectors.toList());
            mdmProcessRouteWorkingVO.setMdmProcessProcedureWorkList(workList);
            procedureList.add(mdmProcessRouteWorkingVO);
        }
        if (!procedureList.isEmpty()) {
            // 根据 orderNum 进行排序
            procedureList.sort(Comparator.comparingInt(MdmProcessRouteWorkingVO::getOrderNum));
        }
        mdmProcessRouteDTO.setProcedureList(procedureList);
        return mdmProcessRouteDTO;
    }

    /**
     * 导出excel 表格
     *
     * @param mdmProcessRoute 查询条件
     * @param ids             导出指定ID
     * @return excel 文件流
     */
    @Override
    public List<MdmProcessRouteExcelVO> export(MdmProcessRouteEntity mdmProcessRoute, Long[] ids) {
        // 获取工艺路线列表
        List<MdmProcessRouteEntity> routerList = this.list(
                Wrappers.lambdaQuery(mdmProcessRoute).in(ArrayUtil.isNotEmpty(ids), MdmProcessRouteEntity::getId, ids));
        List<MdmProcessRouteExcelVO> rList = new ArrayList<>();
        for (MdmProcessRouteEntity item : routerList) {
            MdmProcessRouteExcelVO mdmProcessRouteExcelVO = new MdmProcessRouteExcelVO();
            BeanUtils.copyProperties(item, mdmProcessRouteExcelVO);
            rList.add(mdmProcessRouteExcelVO);
        }
        return rList;
    }

    /**
     * 导入excel 表格
     *
     * @param excelVOList   工艺路线列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public R importProcessRoute(List<MdmProcessRouteExcelVO> excelVOList, BindingResult bindingResult) {
        // 通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();

        // 获取工艺路线列表
        Map<String, String> routeMap = this.list()
                .stream()
                .collect(Collectors.toMap(MdmProcessRouteEntity::getRouteCode, MdmProcessRouteEntity::getRouteCode,
                        (a, b) -> a));
        // 获取物料列表
        Map<String, String> materialMap = mdmMaterialMapper
                .selectList(new LambdaQueryWrapper<MdmMaterialEntity>().eq(MdmMaterialEntity::getStatus, Boolean.TRUE))
                .stream()
                .collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, MdmMaterialEntity::getMaterialName,
                        (a, b) -> a));
        // 获取bom列表
        Map<String, String> bomMap = mdmBomMapper
                .selectList(new LambdaQueryWrapper<MdmBomEntity>().eq(MdmBomEntity::getEnabled, Boolean.TRUE))
                .stream()
                .collect(Collectors.toMap(e -> e.getProductCode() + '_' + e.getVersion(), MdmBomEntity::getVersion, (a, b) -> a));
        List<MdmProcessRouteEntity> processRouteList = new ArrayList<>();
        for (MdmProcessRouteExcelVO mdmProcessRouteExcelVO : excelVOList) {
            Set<String> errorMsg = new HashSet<>();
            // 产品编码校验
            String productName = materialMap.get(mdmProcessRouteExcelVO.getProductCode());
            if (productName == null) {
                errorMsg.add(mdmProcessRouteExcelVO.getProductCode() + "产品编码不存在");
            } else {
                mdmProcessRouteExcelVO.setProductName(productName);
            }
            // bom校验
            String bomVersion = bomMap.get(mdmProcessRouteExcelVO.getProductCode() + '_' + mdmProcessRouteExcelVO.getBomVersion());
            if (bomVersion == null) {
                errorMsg.add(String.format("产品编码【%s】下不存在【%s】bom版本", mdmProcessRouteExcelVO.getProductCode(), mdmProcessRouteExcelVO.getBomVersion()));
            }
            // 工艺路线编码唯一性校验
            boolean routeCode = routeMap.containsKey(mdmProcessRouteExcelVO.getRouteCode());
            if (routeCode) {
                errorMsg.add(mdmProcessRouteExcelVO.getRouteCode() + "工艺路线编码已存在");
            }

            if (CollUtil.isNotEmpty(errorMsg)) {
                // 数据不合法情况
                errorMessageList.add(new ErrorMessage(mdmProcessRouteExcelVO.getLineNum() + 1, errorMsg));
            } else {
                MdmProcessRouteEntity mdmProcessRouteEntity = new MdmProcessRouteEntity();
                BeanUtils.copyProperties(mdmProcessRouteExcelVO, mdmProcessRouteEntity);
                mdmProcessRouteEntity.setStatus(Boolean.TRUE);
                processRouteList.add(mdmProcessRouteEntity);
                routeMap.put(mdmProcessRouteExcelVO.getRouteCode(), mdmProcessRouteExcelVO.getRouteCode());
            }
        }
        // 批量插入工艺路线数据
        this.saveBatch(processRouteList);
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
        return R.ok(null, "工艺路线导入成功");
    }

    /**
     * 组件传参分页查询
     *
     * @param page
     * @param mdmProcessRoute
     * @return
     */
    @Override
    public Page<MdmProcessRouteEntity> selectPage(Page page, MdmProcessRouteSelectDTO mdmProcessRoute) {
        LambdaQueryWrapper<MdmProcessRouteEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(StrUtil.isNotEmpty(mdmProcessRoute.getRouteCode()), MdmProcessRouteEntity::getRouteCode, mdmProcessRoute.getRouteCode())
                .like(StrUtil.isNotEmpty(mdmProcessRoute.getRouteName()), MdmProcessRouteEntity::getRouteName, mdmProcessRoute.getRouteName())
                .like(StrUtil.isNotEmpty(mdmProcessRoute.getWorkshopName()), MdmProcessRouteEntity::getWorkshopName, mdmProcessRoute.getWorkshopName())
                .like(StrUtil.isNotEmpty(mdmProcessRoute.getWorkshopCode()), MdmProcessRouteEntity::getWorkshopCode, mdmProcessRoute.getWorkshopCode())
                .eq(StrUtil.isNotEmpty(mdmProcessRoute.getProductCode()), MdmProcessRouteEntity::getProductCode, mdmProcessRoute.getProductCode());
        return baseMapper.selectPage(page, queryWrapper);
    }

    /**
     * 根据工艺路线ID查询工艺路线下的工序及工序下的工作中心
     *
     * @param routeId 工艺路线ID
     * @return R
     */
    @Override
    @SneakyThrows
    public MdmProcessRouteProcedureVO getProcessWorking(Long routeId) {
        MdmProcessRouteProcedureVO mdmProcessRouteProcedureVO = new MdmProcessRouteProcedureVO();
        MdmProcessRouteEntity processRoute = this.getById(routeId);
        BeanUtils.copyProperties(processRoute, mdmProcessRouteProcedureVO);
        if (processRoute.getProcedureSnapshot() != null) {
            ObjectMapper mapper = new ObjectMapper();
            mdmProcessRouteProcedureVO.setProcedureSnapshot(mapper.readTree(processRoute.getProcedureSnapshot()));
        }
        List<MdmProcessRouteWorkingEntity> mdmProcessRouteWorkingEntities = mdmProcessRouteWorkingMapper.selectList(
                new LambdaQueryWrapper<MdmProcessRouteWorkingEntity>()
                        .eq(MdmProcessRouteWorkingEntity::getRouteCode, processRoute.getRouteCode()));
        List<MdmProcessRouteWorkingVO> procedureList = new ArrayList<>();
        for (MdmProcessRouteWorkingEntity mdmProcessRouteWorkingEntity : mdmProcessRouteWorkingEntities) {
            MdmProcessRouteWorkingVO mdmProcessRouteWorkingVO = new MdmProcessRouteWorkingVO();
            BeanUtils.copyProperties(mdmProcessRouteWorkingEntity, mdmProcessRouteWorkingVO);
            procedureList.add(mdmProcessRouteWorkingVO);
        }
        mdmProcessRouteProcedureVO.setProcedureList(procedureList);
        return mdmProcessRouteProcedureVO;
    }

    /**
     * 查询工艺路线详情
     *
     * @param id
     * @return
     */
    @Override
    public MdmProcessRouteWorkingEntity getProcessWorkingById(Long id) {
        return mdmProcessRouteWorkingMapper.selectById(id);
    }


    /**
     * 新增工艺路线下的工序
     *
     * @param mdmProcessRouteProcedureDTO 工艺路线-工序DTO
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveProcessWorking(MdmProcessRouteProcedureDTO mdmProcessRouteProcedureDTO) {
        if (mdmProcessRouteProcedureDTO == null || mdmProcessRouteProcedureDTO.getId() == null
                || StrUtil.isBlank(mdmProcessRouteProcedureDTO.getRouteCode())) {
            throw new CheckedException("工艺路线及工序关系参数不完整");
        }
        List<MdmProcessRouteWorkingEntity> procedureList = Optional.ofNullable(
                mdmProcessRouteProcedureDTO.getProcedureList()).orElseGet(ArrayList::new);
        validateAndNormalizeProcessGraph(procedureList);
        if (!procedureList.isEmpty() && mdmProcessRouteProcedureDTO.getProcedureSnapshot() == null) {
            throw new CheckedException("工艺路线画布快照不能为空");
        }
        String procedureSnapshot = mdmProcessRouteProcedureDTO.getProcedureSnapshot().toString();
        this.update(new LambdaUpdateWrapper<MdmProcessRouteEntity>()
                .set(MdmProcessRouteEntity::getProcedureSnapshot, procedureSnapshot)
                .eq(MdmProcessRouteEntity::getId, mdmProcessRouteProcedureDTO.getId()));
        // 工艺路线工序修改逻辑
        mdmProcessRouteWorkingMapper.delete(new LambdaQueryWrapper<MdmProcessRouteWorkingEntity>()
                .eq(MdmProcessRouteWorkingEntity::getRouteCode, mdmProcessRouteProcedureDTO.getRouteCode()));
        if (!procedureList.isEmpty()) {
            for (MdmProcessRouteWorkingEntity item : procedureList) {
                MdmProcessRouteWorkingEntity mdmProcessRouteWorkingEntity = new MdmProcessRouteWorkingEntity();
                BeanUtils.copyProperties(item, mdmProcessRouteWorkingEntity);
                mdmProcessRouteWorkingEntity.setId(null);
                mdmProcessRouteWorkingEntity.setRouteName(mdmProcessRouteProcedureDTO.getRouteName());
                mdmProcessRouteWorkingEntity.setRouteCode(mdmProcessRouteProcedureDTO.getRouteCode());
                mdmProcessRouteWorkingMapper.insert(mdmProcessRouteWorkingEntity);
            }
        }
        return Boolean.TRUE;
    }

    /**
     * 服务端以“前置工序”作为DAG权威关系，拒绝重复节点、悬空引用、自环和环路，
     * 并重算后置关系及拓扑层级，避免仅靠前端画布校验导致脏路线进入APS/MES。
     */
    private void validateAndNormalizeProcessGraph(List<MdmProcessRouteWorkingEntity> procedures) {
        if (procedures.isEmpty()) {
            return;
        }
        Map<String, MdmProcessRouteWorkingEntity> byCode = new LinkedHashMap<>();
        for (MdmProcessRouteWorkingEntity procedure : procedures) {
            String code = StrUtil.trim(procedure.getProcessCode());
            if (StrUtil.isBlank(code)) {
                throw new CheckedException("工艺路线存在未选择工序的节点");
            }
            procedure.setProcessCode(code);
            if (byCode.putIfAbsent(code, procedure) != null) {
                throw new CheckedException("工艺路线中工序[" + code + "]重复");
            }
            procedure.setTimeCheck(Boolean.TRUE.equals(procedure.getTimeCheck()));
            if (!Boolean.TRUE.equals(procedure.getTimeCheck())) {
                procedure.setTimeVal(null);
            } else if (procedure.getTimeVal() == null || procedure.getTimeVal().signum() < 0) {
                throw new CheckedException("工序[" + code + "]启用转序时间控制后，转序分钟必须大于等于0");
            }
            procedure.setOverlapEnabled(Boolean.TRUE.equals(procedure.getOverlapEnabled()));
            if (!Boolean.TRUE.equals(procedure.getOverlapEnabled())) {
                procedure.setTransferBatchQuantity(null);
            } else if (procedure.getTransferBatchQuantity() == null
                    || procedure.getTransferBatchQuantity().signum() <= 0) {
                throw new CheckedException("工序[" + code + "]启用重叠执行后，转移批量必须大于0");
            }
        }

        Map<String, LinkedHashSet<String>> predecessors = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> successors = new LinkedHashMap<>();
        byCode.keySet().forEach(code -> {
            predecessors.put(code, new LinkedHashSet<>());
            successors.put(code, new LinkedHashSet<>());
        });
        boolean hasExplicitRelation = procedures.stream().anyMatch(procedure ->
                !normalizeCodes(procedure.getPreRouteCode()).isEmpty()
                        || !normalizeCodes(procedure.getNextRouteCode()).isEmpty());
        List<MdmProcessRouteWorkingEntity> legacyOrder = procedures.stream().sorted(Comparator
                .comparingInt((MdmProcessRouteWorkingEntity item) -> Optional.ofNullable(item.getOrderNum())
                        .orElse(Integer.MAX_VALUE)).thenComparing(MdmProcessRouteWorkingEntity::getProcessCode)).toList();
        if (!hasExplicitRelation && legacyOrder.size() > 1) {
            for (int index = 1; index < legacyOrder.size(); index++) {
                legacyOrder.get(index).setPreRouteCode(new String[]{legacyOrder.get(index - 1).getProcessCode()});
            }
        }
        for (MdmProcessRouteWorkingEntity procedure : procedures) {
            String code = procedure.getProcessCode();
            for (String predecessor : normalizeCodes(procedure.getPreRouteCode())) {
                if (!byCode.containsKey(predecessor)) {
                    throw new CheckedException("工序[" + code + "]引用了不存在的前置工序[" + predecessor + "]");
                }
                if (code.equals(predecessor)) {
                    throw new CheckedException("工序[" + code + "]不能把自身作为前置工序");
                }
                predecessors.get(code).add(predecessor);
                successors.get(predecessor).add(code);
            }
        }
        List<String> terminalProcesses = successors.entrySet().stream().filter(entry -> entry.getValue().isEmpty())
                .map(Map.Entry::getKey).toList();
        if (terminalProcesses.size() != 1) {
            throw new CheckedException("生产工艺路线必须且只能有一道最终工序，当前末端工序："
                    + String.join(",", terminalProcesses));
        }
        String terminal = terminalProcesses.get(0);
        if (Boolean.TRUE.equals(byCode.get(terminal).getOverlapEnabled())) {
            throw new CheckedException("最终工序[" + terminal + "]没有后序，不能启用转移批量重叠");
        }

        Map<String, Integer> indegrees = predecessors.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().size(),
                        (a, b) -> a, LinkedHashMap::new));
        Map<String, Integer> levels = new HashMap<>();
        PriorityQueue<String> ready = new PriorityQueue<>(Comparator
                .comparingInt((String code) -> Optional.ofNullable(byCode.get(code).getOrderNum()).orElse(Integer.MAX_VALUE))
                .thenComparing(Function.identity()));
        indegrees.forEach((code, degree) -> {
            if (degree == 0) {
                ready.add(code);
                levels.put(code, 1);
            }
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            String code = ready.poll();
            visited++;
            for (String successor : successors.get(code)) {
                levels.merge(successor, levels.get(code) + 1, Math::max);
                int remaining = indegrees.computeIfPresent(successor, (key, degree) -> degree - 1);
                if (remaining == 0) {
                    ready.add(successor);
                }
            }
        }
        if (visited != procedures.size()) {
            String cyclic = indegrees.entrySet().stream().filter(entry -> entry.getValue() > 0)
                    .map(Map.Entry::getKey).collect(Collectors.joining(","));
            throw new CheckedException("工艺路线存在环路，涉及工序：" + cyclic);
        }
        procedures.forEach(procedure -> {
            String code = procedure.getProcessCode();
            procedure.setPreRouteCode(predecessors.get(code).toArray(String[]::new));
            procedure.setNextRouteCode(successors.get(code).toArray(String[]::new));
            procedure.setOrderNum(levels.get(code));
        });
    }

    private List<String> normalizeCodes(String[] values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        return Arrays.stream(values).map(StrUtil::trim).filter(StrUtil::isNotBlank).distinct().toList();
    }

    /**
     * 根据工艺路线编码查询工序列表
     *
     * @param routeCode 工艺路线编码
     * @return R
     */
    @Override
    public List<MdmProcessRouteWorkingEntity> getProcedureList(String routeCode) {
        return mdmProcessRouteWorkingMapper.selectList(
                new LambdaQueryWrapper<MdmProcessRouteWorkingEntity>()
                        .eq(MdmProcessRouteWorkingEntity::getRouteCode, routeCode).orderByAsc(MdmProcessRouteWorkingEntity::getOrderNum));
    }

    /**
     * 新增工艺路线下的工序的SOP
     *
     * @param mdmProcessProcedureSopDTO 工艺路线-工序-SOP
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveSop(MdmProcessProcedureSopDTO mdmProcessProcedureSopDTO) {
        // 查询工艺路线-工序数据
        MdmProcessRouteWorkingEntity mdmProcessRouteWorkingEntity = mdmProcessRouteWorkingMapper.selectById(mdmProcessProcedureSopDTO.getId());
        // 删除工艺路线-工序-SOP数据
        mdmProcessProcedureSopMapper.delete(new LambdaQueryWrapper<MdmProcessProcedureSopEntity>()
                .eq(MdmProcessProcedureSopEntity::getRouteCode, mdmProcessRouteWorkingEntity.getRouteCode())
                .eq(MdmProcessProcedureSopEntity::getProcessCode, mdmProcessRouteWorkingEntity.getProcessCode()));
        // 插入工艺路线-工序-SOP数据
        if (!mdmProcessProcedureSopDTO.getSopList().isEmpty()) {
            mdmProcessProcedureSopDTO.getSopList().stream().map(item -> {
                MdmProcessProcedureSopEntity mdmProcessProcedureSopEntity = new MdmProcessProcedureSopEntity();
                mdmProcessProcedureSopEntity.setRouteCode(mdmProcessRouteWorkingEntity.getRouteCode());
                mdmProcessProcedureSopEntity.setProcessCode(mdmProcessRouteWorkingEntity.getProcessCode());
                mdmProcessProcedureSopEntity.setName(item.getName());
                mdmProcessProcedureSopEntity.setFileName(item.getFileName());
                mdmProcessProcedureSopEntity.setUrl(item.getUrl());
                return mdmProcessProcedureSopEntity;
            }).forEach(mdmProcessProcedureSopMapper::insert);
        }
        return Boolean.TRUE;
    }

    /**
     * 查询工艺路线下的工序的SOP
     *
     * @param id 工艺路线-工序id
     * @return R
     */
    @Override
    public List<MdmProcedureSopVO> getSop(Long id) {
        // 查询工艺路线-工序数据
        MdmProcessRouteWorkingEntity mdmProcessRouteWorkingEntity = mdmProcessRouteWorkingMapper.selectById(id);
        return mdmProcessProcedureSopMapper.selectList(new LambdaQueryWrapper<MdmProcessProcedureSopEntity>()
                .eq(MdmProcessProcedureSopEntity::getRouteCode, mdmProcessRouteWorkingEntity.getRouteCode())
                .eq(MdmProcessProcedureSopEntity::getProcessCode, mdmProcessRouteWorkingEntity.getProcessCode())).stream().map(item -> {
            MdmProcedureSopVO mdmProcedureSopVO = new MdmProcedureSopVO();
            BeanUtils.copyProperties(item, mdmProcedureSopVO);
            mdmProcedureSopVO.setFileUrl(item.getFileName());
            return mdmProcedureSopVO;
        }).collect(Collectors.toList());
    }

    /**
     * 查询工艺路线下工序绑定的投料信息
     *
     * @param id 工艺路线-工序id
     * @return R
     */
    @Override
    public List<MdmProcessProcedureMatEntity> getMat(Long id) {
        // 查询工艺路线-工序数据
        MdmProcessRouteWorkingEntity mdmProcessRouteWorkingEntity = mdmProcessRouteWorkingMapper.selectById(id);
        return mdmProcessProcedureMatMapper.selectList(new LambdaQueryWrapper<MdmProcessProcedureMatEntity>()
                .eq(MdmProcessProcedureMatEntity::getRouteCode, mdmProcessRouteWorkingEntity.getRouteCode())
                .eq(MdmProcessProcedureMatEntity::getProcessCode, mdmProcessRouteWorkingEntity.getProcessCode()));
    }

    /**
     * 新增工艺路线下工序绑定的投料信息
     *
     * @param mdmProcessProcedureMatDTO 工艺路线-工序-投料
     * @return R
     */
    @Override
    public Boolean saveMat(MdmProcessProcedureMatDTO mdmProcessProcedureMatDTO) {
        // 查询工艺路线-工序数据
        MdmProcessRouteWorkingEntity mdmProcessRouteWorkingEntity = mdmProcessRouteWorkingMapper.selectById(mdmProcessProcedureMatDTO.getId());
        // 删除工艺路线-工序-投料数据
        mdmProcessProcedureMatMapper.delete(new LambdaQueryWrapper<MdmProcessProcedureMatEntity>()
                .eq(MdmProcessProcedureMatEntity::getRouteCode, mdmProcessRouteWorkingEntity.getRouteCode())
                .eq(MdmProcessProcedureMatEntity::getProcessCode, mdmProcessRouteWorkingEntity.getProcessCode()));
        // 插入工艺路线-工序-投料数据
        if (!mdmProcessProcedureMatDTO.getMatList().isEmpty()) {
            mdmProcessProcedureMatDTO.getMatList().stream().map(item -> {
                MdmProcessProcedureMatEntity mdmProcessProcedureMatEntity = new MdmProcessProcedureMatEntity();
                BeanUtils.copyProperties(item, mdmProcessProcedureMatEntity);
                mdmProcessProcedureMatEntity.setId(null);
                mdmProcessProcedureMatEntity.setRouteCode(mdmProcessRouteWorkingEntity.getRouteCode());
                mdmProcessProcedureMatEntity.setProcessCode(mdmProcessRouteWorkingEntity.getProcessCode());
                return mdmProcessProcedureMatEntity;
            }).forEach(mdmProcessProcedureMatMapper::insert);
        }
        return Boolean.TRUE;
    }

    /**
     * 工艺路线树查询
     *
     * @param queryWrapper 查询条件
     * @return
     */
    @Override
    public List<MdmProcessRouteTreeVO> tree(QueryWrapper<MdmProcessRouteEntity> queryWrapper) {
        // 创建返回体VO
        List<MdmProcessRouteTreeVO> treeList = new ArrayList<>();
        // 获取工艺路线列表
        List<MdmProcessRouteEntity> list = this.list(queryWrapper);

        // 按照productCode分组
        Map<String, List<MdmProcessRouteEntity>> groupedByProduct = list.stream()
                .collect(Collectors.groupingBy(
                        MdmProcessRouteEntity::getProductCode,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        // 处理每个产品分组
        groupedByProduct.forEach((productCode, routes) -> {
            if (!routes.isEmpty()) {
                MdmProcessRouteEntity firstRoute = routes.get(0);
                MdmProcessRouteTreeVO parentNode = new MdmProcessRouteTreeVO();
                parentNode.setId(productCode);
                parentNode.setTitle(String.format("%s(%s)", firstRoute.getProductName(), productCode));
                parentNode.setProductCode(productCode);
                parentNode.setProductName(firstRoute.getProductName());
                parentNode.setProductSpec(firstRoute.getProductSpec());
                parentNode.setProductUnit(firstRoute.getProductUnit());

                // 创建子节点列表
                List<MdmProcessRouteChildrenVO> children = java.util.stream.Stream.concat(
                                routes.stream().filter(route -> Boolean.TRUE.equals(route.getIsDefault())),
                                routes.stream().filter(route -> !Boolean.TRUE.equals(route.getIsDefault()))
                        )
                        .map(route -> {
                            MdmProcessRouteChildrenVO childNode = new MdmProcessRouteChildrenVO();
                            BeanUtils.copyProperties(route, childNode);
                            childNode.setTitle(route.getRouteCode());
                            return childNode;
                        })
                        .collect(Collectors.toList());

                parentNode.setChildren(children);
                treeList.add(parentNode);
            }
        });

        return treeList;
    }

    /**
     * 根据工艺路线工序编码查询工序的SOP
     *
     * @param routeCode   工艺路线编码
     * @param processCode 工艺路线-编码
     * @return
     */
    @Override
    public List<MdmProcedureSopVO> getSopByCode(String routeCode, String processCode) {
        return mdmProcessProcedureSopMapper.selectList(new LambdaQueryWrapper<MdmProcessProcedureSopEntity>()
                .eq(MdmProcessProcedureSopEntity::getRouteCode, routeCode)
                .eq(MdmProcessProcedureSopEntity::getProcessCode, processCode)).stream().map(item -> {
            MdmProcedureSopVO mdmProcedureSopVO = new MdmProcedureSopVO();
            BeanUtils.copyProperties(item, mdmProcedureSopVO);
            mdmProcedureSopVO.setFileUrl(item.getFileName());
            return mdmProcedureSopVO;
        }).collect(Collectors.toList());
    }

    /**
     * 根据工艺路线、工序编码查询工序的投料
     *
     * @param routeCode   工艺路线编码
     * @param processCode 工艺路线-编码
     * @return
     */
    @Override
    public List<MdmProcessProcedureMatEntity> getMatByCode(String routeCode, String processCode) {
        return mdmProcessProcedureMatMapper.selectList(new LambdaQueryWrapper<MdmProcessProcedureMatEntity>()
                .eq(MdmProcessProcedureMatEntity::getRouteCode, routeCode)
                .eq(MdmProcessProcedureMatEntity::getProcessCode, processCode));
    }

    @Override
    public List<MdmProcessProcedureMatEntity> getMaterialAssignments(MdmRouteMaterialAssignmentQueryDTO query) {
        if (query == null || StrUtil.isBlank(query.getFactoryCode())
                || CollUtil.isEmpty(query.getRouteCodes()) || CollUtil.isEmpty(query.getMaterialCodes())) {
            throw new CheckedException("批量查询工序投料映射必须指定工厂、工艺路线和物料");
        }
        if (query.getRouteCodes().size() > 200 || query.getMaterialCodes().size() > 500) {
            throw new CheckedException("单次最多查询200条工艺路线和500个物料");
        }
        return mdmProcessProcedureMatMapper.selectList(new LambdaQueryWrapper<MdmProcessProcedureMatEntity>()
                .eq(MdmProcessProcedureMatEntity::getFactoryCode, query.getFactoryCode())
                .in(MdmProcessProcedureMatEntity::getRouteCode, query.getRouteCodes())
                .in(MdmProcessProcedureMatEntity::getMaterialCode, query.getMaterialCodes())
                .orderByAsc(MdmProcessProcedureMatEntity::getRouteCode)
                .orderByAsc(MdmProcessProcedureMatEntity::getProcessCode)
                .orderByAsc(MdmProcessProcedureMatEntity::getStep));
    }

    /**
     * 根据id设置默认工艺路线
     *
     * @param id id
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean routeDefault(Long id) {
        MdmProcessRouteEntity processRoute = this.getById(id);
        this.update(new LambdaUpdateWrapper<MdmProcessRouteEntity>().set(MdmProcessRouteEntity::getIsDefault, Boolean.FALSE)
                .ne(MdmProcessRouteEntity::getId, id)
                .eq(MdmProcessRouteEntity::getProductCode, processRoute.getProductCode()));
        return this.update(new LambdaUpdateWrapper<MdmProcessRouteEntity>().set(MdmProcessRouteEntity::getIsDefault, Boolean.TRUE)
                .eq(MdmProcessRouteEntity::getId, id));
    }

    /**
     * 查询工艺路线下工序的设置信息
     *
     * @param routeCode   工艺路线编码
     * @param processCode 工序编码
     * @return R
     */
    @Override
    public MdmProcessProcedureSettingEntity getSetByCode(String routeCode, String processCode) {
        return mdmProcessProcedureSettingMapper.selectOne(new LambdaQueryWrapper<MdmProcessProcedureSettingEntity>()
                .eq(MdmProcessProcedureSettingEntity::getRouteCode, routeCode)
                .eq(MdmProcessProcedureSettingEntity::getProcessCode, processCode), false);
    }

    /**
     * 修改工艺路线下工序的设置信息
     *
     * @param mdmProcessProcedureSetting 工艺路线-工序设置
     * @return R
     */
    @Override
    public Boolean saveOrUpdateSet(MdmProcessProcedureSettingEntity mdmProcessProcedureSetting) {
        if (mdmProcessProcedureSetting.getIsMatWeightConvert() && mdmProcessProcedureSetting.getMatWeightConvert() == null) {
            throw new CheckedException("请输入产料单位重量");
        }
        return mdmProcessProcedureSettingMapper.insertOrUpdate(mdmProcessProcedureSetting);
    }

}

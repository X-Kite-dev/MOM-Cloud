package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.yulichang.base.MPJBaseServiceImpl;
import com.kitex.admin.api.entity.SysDictItemEntity;
import com.kitex.admin.api.feign.RemoteDictService;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.mdm.entity.MdmEmployeeTeamEntity;
import com.kitex.mom.mdm.entity.MdmTeamEntity;
import com.kitex.mom.mdm.mapper.MdmEmployeeTeamMapper;
import com.kitex.mom.mdm.mapper.MdmTeamMapper;
import com.kitex.mom.mdm.service.MdmTeamService;
import com.kitex.mom.mdm.vo.excel.MdmTeamExcelVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 班组管理
 *
 * @author kitex
 * @date 2025-01-20 10:00:00
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MdmTeamServiceImpl extends MPJBaseServiceImpl<MdmTeamMapper, MdmTeamEntity> implements MdmTeamService {

    private final MdmEmployeeTeamMapper mdmEmployeeTeamMapper;

    private final RemoteDictService remoteDictService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(MdmTeamEntity mdmTeam) {
        // 保存班组基本信息
        boolean saveResult = save(mdmTeam);
        if (!saveResult) {
            return false;
        }

        // 保存班组员工关联信息
        if (CollUtil.isNotEmpty(mdmTeam.getMdmEmployeeTeamList())) {
            for (MdmEmployeeTeamEntity employeeTeam : mdmTeam.getMdmEmployeeTeamList()) {
                employeeTeam.setTeamId(mdmTeam.getId());
                // 设置班组相关信息
                employeeTeam.setTeamCode(mdmTeam.getCode());
                employeeTeam.setTeamName(mdmTeam.getName());
                mdmEmployeeTeamMapper.insert(employeeTeam);
            }
        }

        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(MdmTeamEntity mdmTeam) {
        // 更新班组基本信息
        boolean updateResult = updateById(mdmTeam);
        if (!updateResult) {
            return false;
        }

        // 删除原有的员工关联
        LambdaQueryWrapper<MdmEmployeeTeamEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(MdmEmployeeTeamEntity::getTeamId, mdmTeam.getId());
        mdmEmployeeTeamMapper.delete(wrapper);

        // 保存新的员工关联信息
        if (CollUtil.isNotEmpty(mdmTeam.getMdmEmployeeTeamList())) {
            for (MdmEmployeeTeamEntity employeeTeam : mdmTeam.getMdmEmployeeTeamList()) {
                employeeTeam.setId(null);
                employeeTeam.setTeamId(mdmTeam.getId());
                // 设置班组相关信息
                employeeTeam.setTeamCode(mdmTeam.getCode());
                employeeTeam.setTeamName(mdmTeam.getName());
                mdmEmployeeTeamMapper.insert(employeeTeam);
            }
        }

        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(Long[] ids) {
        // 删除班组基本信息
        boolean removeResult = removeByIds(CollUtil.toList(ids));
        if (!removeResult) {
            return false;
        }

        // 删除班组员工关联信息
        LambdaQueryWrapper<MdmEmployeeTeamEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.in(MdmEmployeeTeamEntity::getTeamId, CollUtil.toList(ids));
        mdmEmployeeTeamMapper.delete(wrapper);

        return true;
    }

    @Override
    public List<MdmEmployeeTeamEntity> getUserByTeamCode(String code) {
        MdmTeamEntity oneDeep = this.getOneDeep(new LambdaQueryWrapper<MdmTeamEntity>().eq(MdmTeamEntity::getCode, code));
        return oneDeep.getMdmEmployeeTeamList();
    }

    /**
     * 根据班组编码查询班组下的员工
     *
     * @param code 班组编码
     * @return R
     */
    @Override
    public List<MdmTeamExcelVO> exportTeam(MdmTeamEntity mdmTeam, Long[] ids) {
        // 获取模块字典
        Map<String, String> moduleType = remoteDictService.getDictByType("modules").getData().stream()
                .collect(Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel, (a, b) -> a));
        return this.list(Wrappers.lambdaQuery(mdmTeam).in(ArrayUtil.isNotEmpty(ids), MdmTeamEntity::getId, ids))
                .stream()
                .map(team -> {
                    MdmTeamExcelVO mdmTeamExcelVO = new MdmTeamExcelVO();
                    BeanUtils.copyProperties(team, mdmTeamExcelVO);
                    if (!moduleType.isEmpty()) {
                        mdmTeamExcelVO.setType(moduleType.get(team.getType()));
                    }
                    return mdmTeamExcelVO;
                }).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R importTeam(List<MdmTeamExcelVO> excelVOList, BindingResult bindingResult) {
        // 通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();
        // 获取模块字典
        Map<String, String> moduleType = remoteDictService.getDictByType("modules").getData().stream()
                .collect(Collectors.toMap(SysDictItemEntity::getLabel, SysDictItemEntity::getItemValue, (a, b) -> a));
        // 获取班组列表
        Map<String, String> teamMap = this.list()
                .stream()
                .collect(Collectors.toMap(MdmTeamEntity::getCode, MdmTeamEntity::getCode,
                        (a, b) -> a));
        List<MdmTeamEntity> mdmTeamList = new ArrayList<>();
        for (MdmTeamExcelVO excelVO : excelVOList) {
            Set<String> errorMsg = new HashSet<>();
            boolean code = teamMap.containsKey(excelVO.getCode());
            if (code) {
                errorMsg.add(excelVO.getCode() + "班组编码已存在");
            }
            // 校验类型是否存在
            String typeValue = moduleType.get(excelVO.getType());
            if (StrUtil.isEmpty(typeValue)) {
                errorMsg.add(excelVO.getType() + "类型不存在");
            } else {
                excelVO.setType(typeValue);
            }
            if (CollUtil.isEmpty(errorMsg)) {
                MdmTeamEntity mdmTeamEntity = insertExcel(excelVO);
                mdmTeamList.add(mdmTeamEntity);
                teamMap.put(mdmTeamEntity.getCode(), mdmTeamEntity.getCode());
            } else {
                // 数据不合法情况
                errorMessageList.add(new ErrorMessage(excelVO.getLineNum() + 1, errorMsg));
            }
        }
        // 批量插入供应商
        this.saveBatch(mdmTeamList);
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
        return R.ok(null, "班组导入成功");
    }

    private MdmTeamEntity insertExcel(MdmTeamExcelVO excelVO) {
        MdmTeamEntity mdmTeamEntity = new MdmTeamEntity();
        BeanUtils.copyProperties(excelVO, mdmTeamEntity);
        return mdmTeamEntity;
    }
}

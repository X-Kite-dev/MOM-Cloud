package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.yulichang.base.MPJBaseServiceImpl;
import com.kitex.admin.api.entity.SysUserEntity;
import com.kitex.admin.api.feign.RemoteUserService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.mdm.entity.MdmEmployeeEntity;
import com.kitex.mom.mdm.entity.MdmEmployeeTeamEntity;
import com.kitex.mom.mdm.enums.EmployeeGenderEnum;
import com.kitex.mom.mdm.enums.EmployeeStatusEnum;
import com.kitex.mom.mdm.mapper.MdmEmployeeMapper;
import com.kitex.mom.mdm.mapper.MdmEmployeeTeamMapper;
import com.kitex.mom.mdm.mapper.MdmTeamMapper;
import com.kitex.mom.mdm.service.MdmEmployeeService;
import com.kitex.mom.mdm.util.ValidatorUtil;
import com.kitex.mom.mdm.vo.excel.MdmEmployeeExcelVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 员工
 *
 * @author kitex
 * @date 2025-01-20 10:00:00
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MdmEmployeeServiceImpl extends MPJBaseServiceImpl<MdmEmployeeMapper, MdmEmployeeEntity> implements MdmEmployeeService {

    private final MdmEmployeeTeamMapper mdmEmployeeTeamMapper;
    private final MdmTeamMapper mdmTeamMapper;
    private final RemoteUserService remoteUserService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(MdmEmployeeEntity mdmEmployee) {
        // 保存员工基本信息
        boolean saveResult = save(mdmEmployee);
        if (!saveResult) {
            return false;
        }

        // 保存员工班组关联信息
        if (CollUtil.isNotEmpty(mdmEmployee.getMdmEmployeeTeamList())) {
            for (MdmEmployeeTeamEntity employeeTeam : mdmEmployee.getMdmEmployeeTeamList()) {
                employeeTeam.setEmployeeId(mdmEmployee.getId());
                // 设置员工相关信息
                employeeTeam.setEmployeeCode(mdmEmployee.getEmployeeCode());
                employeeTeam.setEmployeeName(mdmEmployee.getName());
                mdmEmployeeTeamMapper.insert(employeeTeam);
            }
        }

        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(MdmEmployeeEntity mdmEmployee) {
        // 更新员工基本信息
        boolean updateResult = updateById(mdmEmployee);
        if (!updateResult) {
            return false;
        }

        // 删除原有的班组关联
        LambdaQueryWrapper<MdmEmployeeTeamEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(MdmEmployeeTeamEntity::getEmployeeId, mdmEmployee.getId());
        mdmEmployeeTeamMapper.delete(wrapper);

        // 保存新的班组关联信息
        if (CollUtil.isNotEmpty(mdmEmployee.getMdmEmployeeTeamList())) {
            for (MdmEmployeeTeamEntity employeeTeam : mdmEmployee.getMdmEmployeeTeamList()) {
                employeeTeam.setEmployeeId(mdmEmployee.getId());
                // 设置员工相关信息
                employeeTeam.setEmployeeCode(mdmEmployee.getEmployeeCode());
                employeeTeam.setEmployeeName(mdmEmployee.getName());
                mdmEmployeeTeamMapper.insert(employeeTeam);
            }
        }

        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(Long[] ids) {
        // 删除员工基本信息
        boolean removeResult = removeByIds(CollUtil.toList(ids));
        if (!removeResult) {
            return false;
        }

        // 删除员工班组关联信息
        LambdaQueryWrapper<MdmEmployeeTeamEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.in(MdmEmployeeTeamEntity::getEmployeeId, CollUtil.toList(ids));
        mdmEmployeeTeamMapper.delete(wrapper);

        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R importEmployee(List<MdmEmployeeExcelVO> excelVOList, BindingResult bindingResult) {
        // 通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();
        // 查询员工数据
        Map<String, String> employeeMap = this.list(new LambdaQueryWrapper<MdmEmployeeEntity>().eq(MdmEmployeeEntity::getStatus, EmployeeStatusEnum.ACTIVE.getCode()))
                .stream()
                .collect(Collectors.toMap(MdmEmployeeEntity::getEmployeeCode, MdmEmployeeEntity::getName, (v1, v2) -> v1));
        // 数据转换和保存
        List<MdmEmployeeEntity> insertList = new ArrayList<>();
        for (MdmEmployeeExcelVO excelVO : excelVOList) {
            Set<String> errorMsg = new HashSet<>();
            // 校验员工编码是否存在
            boolean employeeCode = employeeMap.containsKey(excelVO.getEmployeeCode());
            if (employeeCode) {
                errorMsg.add("员工编码已存在");
            }
            // 校验入职日期是否大于今天
            if (excelVO.getHireDate().isAfter(LocalDate.now())) {
                errorMsg.add("入职日期不能晚于当前时间");
            }
            // 校验手机号
            boolean phone = ValidatorUtil.isMobile(excelVO.getPhone());
            if (!phone) {
                errorMsg.add("手机号格式错误");
            }
            // 校验邮箱
            boolean email = ValidatorUtil.isEmail(excelVO.getEmail());
            if (!email) {
                errorMsg.add("邮箱格式错误");
            }
            // 校验身份证号
            boolean idCard = ValidatorUtil.isIdCard(excelVO.getIdCard());
            if (!idCard) {
                errorMsg.add("身份证号格式错误");
            }
            // 数据合法情况
            if (CollUtil.isEmpty(errorMsg)) {
                MdmEmployeeEntity insertExcel = new MdmEmployeeEntity();
                BeanUtil.copyProperties(excelVO, insertExcel);
                insertExcel.setStatus(EmployeeStatusEnum.ACTIVE.getCode());
                insertExcel.setGender(EmployeeGenderEnum.getCodeByName(excelVO.getGender()));
                insertList.add(insertExcel);
                employeeMap.put(excelVO.getEmployeeCode(), excelVO.getName());
            } else {
                // 数据不合法情况
                errorMessageList.add(new ErrorMessage(excelVO.getLineNum() + 1, errorMsg));
            }
        }
        this.saveBatch(insertList);
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
        return R.ok(null, "员工导入成功");
    }

    @Override
    public List<MdmEmployeeExcelVO> exportEmployee(MdmEmployeeEntity mdmEmployee, Long[] ids) {
        List<MdmEmployeeEntity> employeeList;

        if (ids != null && ids.length > 0) {
            // 导出指定ID的数据
            employeeList = listByIds(CollUtil.toList(ids));
        } else {
            // 根据查询条件导出
            LambdaQueryWrapper<MdmEmployeeEntity> wrapper = Wrappers.lambdaQuery();
            wrapper.like(StrUtil.isNotBlank(mdmEmployee.getEmployeeCode()), MdmEmployeeEntity::getEmployeeCode, mdmEmployee.getEmployeeCode())
                    .like(StrUtil.isNotBlank(mdmEmployee.getName()), MdmEmployeeEntity::getName, mdmEmployee.getName())
                    .eq(StrUtil.isNotBlank(mdmEmployee.getGender()), MdmEmployeeEntity::getGender, mdmEmployee.getGender())
                    .eq(StrUtil.isNotBlank(mdmEmployee.getStatus()), MdmEmployeeEntity::getStatus, mdmEmployee.getStatus())
                    .eq(StrUtil.isNotBlank(mdmEmployee.getFactoryCode()), MdmEmployeeEntity::getFactoryCode, mdmEmployee.getFactoryCode())
                    .orderByDesc(MdmEmployeeEntity::getCreateTime);
            employeeList = list(wrapper);
        }

        // 转换为Excel VO
        List<MdmEmployeeExcelVO> excelVOList = new ArrayList<>();
        for (MdmEmployeeEntity employee : employeeList) {
            MdmEmployeeExcelVO excelVO = BeanUtil.copyProperties(employee, MdmEmployeeExcelVO.class);
            excelVO.setGender(EmployeeGenderEnum.getNameByCode(employee.getGender()));
            excelVO.setStatus(EmployeeStatusEnum.getNameByCode(employee.getGender()));
            excelVOList.add(excelVO);
        }

        return excelVOList;
    }

    /**
     * 同步员工到用户表
     *
     * @param ids 员工ids
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean syncEmployeeToUser(Long[] ids) {
        if (ids == null || ids.length == 0) {
            return Boolean.TRUE;
        }
        ArrayList<Long> employeeIds = CollUtil.toList(ids);
        // 查询未同步至系统用户的员工信息
        List<MdmEmployeeEntity> employeeList = this.list(new LambdaQueryWrapper<MdmEmployeeEntity>()
                .in(MdmEmployeeEntity::getId, employeeIds)
                .eq(MdmEmployeeEntity::getSyncStatus, Boolean.FALSE));
        if (CollUtil.isEmpty(employeeList)) {
            return Boolean.TRUE;
        }
        List<SysUserEntity> userList = new ArrayList<>();
        employeeList.forEach(employee -> {
            SysUserEntity sysUser = new SysUserEntity();
            sysUser.setUsername(employee.getEmployeeCode());
            sysUser.setPhone(employee.getPhone());
            sysUser.setEmail(employee.getEmail());
            sysUser.setName(employee.getName());
            sysUser.setUpdateBy(employee.getEmployeeCode());
            userList.add(sysUser);
        });
        // 请求系统用户接口进行同步员工信息至系统用户
        R<Boolean> booleanR = remoteUserService.syncEmployeeToUser(userList);
        if (!booleanR.isOk()) {
            throw new CheckedException("同步员工信息至系统用户失败：" + booleanR.getMsg());
        }
        // 修改员工同步状态
        this.update(new LambdaUpdateWrapper<MdmEmployeeEntity>()
                .set(MdmEmployeeEntity::getSyncStatus, Boolean.TRUE)
                .in(MdmEmployeeEntity::getId, employeeIds));
        return Boolean.TRUE;
    }
}
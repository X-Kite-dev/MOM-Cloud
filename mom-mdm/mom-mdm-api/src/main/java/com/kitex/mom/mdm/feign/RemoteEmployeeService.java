package com.kitex.mom.mdm.feign;

import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.Constants;
import com.kitex.mom.mdm.entity.MdmEmployeeEntity;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 员工远程服务
 */
@FeignClient(contextId = "remoteEmployeeService", value = Constants.Name)
public interface RemoteEmployeeService {

    /**
     * 根据员工ID获取员工信息
     *
     * @param id 员工ID
     * @return 员工信息
     */
    @GetMapping("/mdmEmployee/{id}")
    R<MdmEmployeeEntity> getEmployeeById(@PathVariable("id") Long id);

    /**
     * 根据员工编码获取员工信息
     *
     * @param employeeCode 员工编码
     * @return 员工信息
     */
    @GetMapping("/mdmEmployee/details")
    R<List<MdmEmployeeEntity>> getEmployeeByCode(@RequestParam("employeeCode") String employeeCode);

    /**
     * 根据部门获取员工列表
     *
     * @param department 部门
     * @return 员工列表
     */
    @GetMapping("/mdmEmployee/list")
    R<List<MdmEmployeeEntity>> getEmployeeByDepartment(@RequestParam("department") String department);
} 
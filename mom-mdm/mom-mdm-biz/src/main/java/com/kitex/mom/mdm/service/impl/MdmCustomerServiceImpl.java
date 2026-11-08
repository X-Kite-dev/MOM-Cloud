package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.mdm.entity.MdmCustomerDeliverEntity;
import com.kitex.mom.mdm.entity.MdmCustomerEntity;
import com.kitex.mom.mdm.mapper.MdmCustomerDeliverMapper;
import com.kitex.mom.mdm.mapper.MdmCustomerMapper;
import com.kitex.mom.mdm.service.MdmCustomerService;
import com.kitex.mom.mdm.utils.DynamicRefreshUtils;
import com.kitex.mom.mdm.vo.excel.MdmCustomerExcelVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.util.*;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 客户表服务实现类
 *
 * @author kitex
 * @date 2024-10-27 16:22:18
 */
@Service
@AllArgsConstructor
public class MdmCustomerServiceImpl extends ServiceImpl<MdmCustomerMapper, MdmCustomerEntity>
        implements MdmCustomerService {

    private final MdmCustomerDeliverMapper mdmCustomerDeliverMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(MdmCustomerEntity mdmCustomer) {
        validateMaintenance(mdmCustomer);
        if (baseMapper.insert(mdmCustomer) != 1) {
            throw new CheckedException("客户主档未保存");
        }
        DynamicRefreshUtils.refreshMdm(mdmCustomer);
        for (MdmCustomerDeliverEntity mdmCustomerDeliver : mdmCustomer.getMdmCustomerDeliverList()) {
            mdmCustomerDeliver.setCustomerId(mdmCustomer.getId());
            mdmCustomerDeliver.setFactoryCode(mdmCustomer.getFactoryCode());
            mdmCustomerDeliver.setTenantId(mdmCustomer.getTenantId());
            if (mdmCustomerDeliverMapper.insert(mdmCustomerDeliver) != 1) {
                throw new CheckedException("客户发货地址未保存，整单回滚");
            }
        }

        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(MdmCustomerEntity mdmCustomer) {
        validateMaintenance(mdmCustomer);
        MdmCustomerEntity original = getOne(Wrappers.<MdmCustomerEntity>lambdaQuery()
                .eq(MdmCustomerEntity::getId, mdmCustomer.getId())
                .eq(MdmCustomerEntity::getTenantId, mdmCustomer.getTenantId())
                .eq(MdmCustomerEntity::getFactoryCode, mdmCustomer.getFactoryCode()).last("FOR UPDATE"));
        if (original == null || !Objects.equals(original.getCustomerCode(), mdmCustomer.getCustomerCode())) {
            throw new CheckedException("客户不存在、组织不符或编码被改绑");
        }
        if (baseMapper.updateById(mdmCustomer) != 1) {
            throw new CheckedException("客户主档未更新，发货地址保持原样");
        }
        DynamicRefreshUtils.refreshMdm(mdmCustomer);
        mdmCustomerDeliverMapper.delete(Wrappers.<MdmCustomerDeliverEntity>lambdaQuery()
                .eq(MdmCustomerDeliverEntity::getCustomerId, mdmCustomer.getId()));
        for (MdmCustomerDeliverEntity mdmCustomerDeliver : mdmCustomer.getMdmCustomerDeliverList()) {
            mdmCustomerDeliver.setId(null);
            mdmCustomerDeliver.setCustomerId(mdmCustomer.getId());
            mdmCustomerDeliver.setFactoryCode(mdmCustomer.getFactoryCode());
            mdmCustomerDeliver.setTenantId(mdmCustomer.getTenantId());
            if (mdmCustomerDeliverMapper.insert(mdmCustomerDeliver) != 1) {
                throw new CheckedException("客户发货地址未保存，整单回滚");
            }
        }
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(Long[] ids) {
        baseMapper.deleteBatchIds(CollUtil.toList(ids));
        mdmCustomerDeliverMapper.delete(Wrappers.<MdmCustomerDeliverEntity>lambdaQuery().in(MdmCustomerDeliverEntity::getCustomerId, ids));
        return Boolean.TRUE;
    }

    /**
     * 维护入口核当前组织和完整地址，外部客户仓库保留文本合同，不擅自认领内部仓库。
     * @param customer 本次提交的客户及发货地址
     */
    private void validateMaintenance(MdmCustomerEntity customer) {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        if (customer == null || tenant == null || tenant <= 0 || factory == null || factory.isBlank()
                || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
            throw new CheckedException("客户维护需要明确租户及实际工厂");
        }
        if ((customer.getTenantId() != null && !tenant.equals(customer.getTenantId()))
                || (customer.getFactoryCode() != null && !factory.equals(customer.getFactoryCode()))) {
            throw new CheckedException("客户组织与当前上下文不一致");
        }
        customer.setTenantId(tenant);
        customer.setFactoryCode(factory);
        if (customer.getCustomerCode() == null || customer.getCustomerCode().isBlank()
                || customer.getCustomerName() == null || customer.getCustomerName().isBlank()) {
            throw new CheckedException("客户编码和名称不能为空");
        }
        if (customer.getMdmCustomerDeliverList() == null || customer.getMdmCustomerDeliverList().isEmpty()) {
            throw new CheckedException("请维护客户发货地址");
        }
        Set<String> warehouses = new HashSet<>();
        for (MdmCustomerDeliverEntity address : customer.getMdmCustomerDeliverList()) {
            if (address == null || address.getWarehouse() == null || address.getWarehouse().isBlank()
                    || address.getAddress() == null || address.getAddress().isBlank()) {
                throw new CheckedException("客户发货仓库和地址不能为空");
            }
            String warehouse = address.getWarehouse().trim();
            if (!warehouses.add(warehouse)) {
                throw new CheckedException("同一客户发货仓库不能重复");
            }
            address.setWarehouse(warehouse);
        }
    }

    /**
     * 导出
     *
     * @param mdmCustomer
     * @param ids
     * @return
     */
    @Override
    public List<MdmCustomerExcelVO> export(MdmCustomerEntity mdmCustomer, Long[] ids) {
        return this.list(Wrappers.lambdaQuery(mdmCustomer).in(ArrayUtil.isNotEmpty(ids), MdmCustomerEntity::getId, ids))
                .stream()
                .map(mdmCustomer1 -> {
                    MdmCustomerExcelVO mdmCustomerExcelVO = new MdmCustomerExcelVO();
                    BeanUtil.copyProperties(mdmCustomer1, mdmCustomerExcelVO);
                    return mdmCustomerExcelVO;
                })
                .collect(Collectors.toList());

    }

    /**
     * 车间管理导入
     *
     * @param mdmCustomerList
     * @param bindingResult
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public R importExcel(List<MdmCustomerExcelVO> mdmCustomerList, BindingResult bindingResult) {

        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();
        // 获取已有客户信息
        Map<String, MdmCustomerEntity> mdmCustomerMap = this.list()
                .stream()
                .collect(Collectors.toMap(MdmCustomerEntity::getCustomerCode, Function.identity(), (a, b) -> a));

        List<MdmCustomerEntity> saveList = new ArrayList<>();
        for (MdmCustomerExcelVO mdmCustomerVo : mdmCustomerList) {
            Set<String> errorMsg = new HashSet<>();

            // 校验客户编码
            if (StrUtil.isEmpty(mdmCustomerVo.getCustomerCode().trim())) {
                errorMsg.add("客户编码为空");
            } else if (mdmCustomerMap.containsKey(mdmCustomerVo.getCustomerCode().trim())) {
                errorMsg.add("客户编码已存在");
            }
            if (StrUtil.isEmpty(mdmCustomerVo.getCustomerName())) {
                errorMsg.add("客户名称为空");
            }

            // 状态
            mdmCustomerVo.setStatus("1");

            if (CollUtil.isEmpty(errorMsg)) {
                // 数据合法情况
                MdmCustomerEntity mdmCustomerEntity = insertExcel(mdmCustomerVo);
                saveList.add(mdmCustomerEntity);
                mdmCustomerMap.put(mdmCustomerVo.getCustomerCode(), mdmCustomerEntity);

            } else {
                // 数据不合法情况
                errorMessageList.add(new ErrorMessage(mdmCustomerVo.getLineNum() + 1, errorMsg));
            }

        }

        this.saveBatch(saveList);
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
        return R.ok(null, "导入成功");
    }

    private MdmCustomerEntity insertExcel(MdmCustomerExcelVO excelVO) {
        MdmCustomerEntity customer = new MdmCustomerEntity();
        BeanUtils.copyProperties(excelVO, customer);
        return customer;
    }

}

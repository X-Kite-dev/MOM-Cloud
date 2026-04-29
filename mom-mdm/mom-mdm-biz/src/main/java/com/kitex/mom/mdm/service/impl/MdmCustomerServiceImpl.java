package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.util.R;
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
        baseMapper.insert(mdmCustomer);
        DynamicRefreshUtils.refreshMdm(mdmCustomer);
        for (MdmCustomerDeliverEntity mdmCustomerDeliver : mdmCustomer.getMdmCustomerDeliverList()) {
            mdmCustomerDeliver.setCustomerId(mdmCustomer.getId());
            mdmCustomerDeliver.setFactoryCode(mdmCustomer.getFactoryCode());
            mdmCustomerDeliverMapper.insert(mdmCustomerDeliver);
        }

        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(MdmCustomerEntity mdmCustomer) {
        baseMapper.updateById(mdmCustomer);
        DynamicRefreshUtils.refreshMdm(mdmCustomer);
        mdmCustomerDeliverMapper.delete(Wrappers.<MdmCustomerDeliverEntity>lambdaQuery()
                .eq(MdmCustomerDeliverEntity::getCustomerId, mdmCustomer.getId()));
        for (MdmCustomerDeliverEntity mdmCustomerDeliver : mdmCustomer.getMdmCustomerDeliverList()) {
            mdmCustomerDeliver.setId(null);
            mdmCustomerDeliver.setCustomerId(mdmCustomer.getId());
            mdmCustomerDeliver.setFactoryCode(mdmCustomer.getFactoryCode());
            mdmCustomerDeliverMapper.insert(mdmCustomerDeliver);
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
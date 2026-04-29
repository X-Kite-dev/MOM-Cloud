package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.mdm.entity.MdmBadConfigEntity;
import com.kitex.mom.mdm.enums.MdmBadConfigTypeEnum;
import com.kitex.mom.mdm.mapper.MdmBadConfigMapper;
import com.kitex.mom.mdm.service.MdmBadConfigService;
import com.kitex.mom.mdm.vo.excel.MdmBadExcelVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 不良品项管理
 *
 * @author kitex
 * @since 2025-05-17
 */
@Service
@AllArgsConstructor
public class MdmBadConfigServiceImpl extends ServiceImpl<MdmBadConfigMapper, MdmBadConfigEntity> implements MdmBadConfigService {

    public static final String RootNode = "-1";

    /**
     * 查询不良品项树
     *
     * @param mdmBadConfig
     * @return
     */
    @Override
    public List<MdmBadConfigEntity> tree(MdmBadConfigEntity mdmBadConfig) {
        if (StrUtil.isNotBlank(mdmBadConfig.getBadCode()) || StrUtil.isNotBlank(mdmBadConfig.getBadName())) {
            return this.list(new LambdaQueryWrapper<MdmBadConfigEntity>()
                    .like(StrUtil.isNotBlank(mdmBadConfig.getBadCode()), MdmBadConfigEntity::getBadCode, mdmBadConfig.getBadCode())
                    .like(StrUtil.isNotBlank(mdmBadConfig.getBadName()), MdmBadConfigEntity::getBadName, mdmBadConfig.getBadName())
                    .orderByDesc(MdmBadConfigEntity::getCreateTime));
        }
        List<MdmBadConfigEntity> allList = this.list(new LambdaQueryWrapper<MdmBadConfigEntity>().orderByDesc(MdmBadConfigEntity::getCreateTime));
        String parent = StrUtil.isNotBlank(mdmBadConfig.getParentCode()) ? mdmBadConfig.getParentCode() : RootNode;
        List<MdmBadConfigEntity> fatherList = allList.stream().filter(item -> parent.equals(item.getParentCode())).toList();
        return buildTree(fatherList, allList, mdmBadConfig.getChooseClassify());
    }

    /**
     * 构建树
     *
     * @param fatherList
     * @param allList
     * @return
     */
    private List<MdmBadConfigEntity> buildTree(List<MdmBadConfigEntity> fatherList, List<MdmBadConfigEntity> allList, Boolean chooseClassify) {
        for (MdmBadConfigEntity father : fatherList) {
            List<MdmBadConfigEntity> children = allList.stream().filter(item -> item.getParentCode().equals(father.getBadCode())).toList();
            if (CollUtil.isNotEmpty(children)) {
                father.setChildren(children);
                if (!chooseClassify) {
                    if (MdmBadConfigTypeEnum.CLASSIFY.name().equals(father.getBadType())) {
                        father.setDisabled(Boolean.TRUE);
                    }
                }
                buildTree(father.getChildren(), allList, chooseClassify);
            }
        }
        return fatherList;
    }

    /**
     * 新增不良品项
     *
     * @param mdmBadConfig
     */
    @Override
    public void add(MdmBadConfigEntity mdmBadConfig) {
        long count = this.count(new LambdaQueryWrapper<MdmBadConfigEntity>()
                .eq(MdmBadConfigEntity::getBadCode, mdmBadConfig.getBadCode()));
        if (count > 0) {
            throw new RuntimeException("存在重复不良品项编码！");
        }
        if (MdmBadConfigTypeEnum.CLASSIFY.name().equals(mdmBadConfig.getBadType())) {
            mdmBadConfig.setStatus("1");
        }
        this.save(mdmBadConfig);
    }

    /**
     * 导入
     *
     * @param list
     * @param bindingResult
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public R importExcel(List<MdmBadExcelVO> list, BindingResult bindingResult) {
        // 通用校验获取失败的数据
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();

        Map<String, MdmBadConfigEntity> badConfigMap = this.list()
                .stream()
                .collect(Collectors.toMap(MdmBadConfigEntity::getBadCode, Function.identity(), (a, b) -> a));
        List<MdmBadConfigEntity> saveList = new ArrayList<>();
        for (MdmBadExcelVO excelVO : list) {
            Set<String> errorMsg = new HashSet<>();
            // 校验编码是否重复
            if (badConfigMap.containsKey(excelVO.getBadCode().trim())) {
                errorMsg.add("不良编码已存在");
            }

            if (MdmBadConfigTypeEnum.CLASSIFY.getName().equals(excelVO.getBadType())) {
                excelVO.setBadType(MdmBadConfigTypeEnum.CLASSIFY.name());
            } else {
                excelVO.setBadType(MdmBadConfigTypeEnum.ITEM.name());
                excelVO.setStatus("1");
            }

            if (StrUtil.isEmpty(excelVO.getParentCode())) {
                excelVO.setParentCode("-1");
            }

            if (CollUtil.isEmpty(errorMsg)) {
                // 数据合法情况
                MdmBadConfigEntity badConfig = insertExcel(excelVO);
                saveList.add(badConfig);
                badConfigMap.put(excelVO.getBadCode(), badConfig);
            } else {
                // 数据不合法情况
                errorMessageList.add(new ErrorMessage(excelVO.getLineNum() + 1, errorMsg));
            }

        }
        // 保存信息
        this.saveBatch(saveList);
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
        return R.ok(null, "导入成功");
    }

    private MdmBadConfigEntity insertExcel(MdmBadExcelVO excelVO) {
        MdmBadConfigEntity badConfig = new MdmBadConfigEntity();
        BeanUtils.copyProperties(excelVO, badConfig);
        return badConfig;
    }

}

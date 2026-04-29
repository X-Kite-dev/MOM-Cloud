package com.kitex.mom.mdm.service;

import cn.hutool.core.lang.tree.Tree;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmMaterialGroupEntity;
import com.kitex.mom.mdm.vo.excel.MdmMaterialGroupExcelVO;
import org.springframework.validation.BindingResult;

import java.util.ArrayList;
import java.util.List;

public interface MdmMaterialGroupService extends IService<MdmMaterialGroupEntity> {

    /**
     * 树形数据
     *
     * @param parentId  父节点ID
     * @param groupCode 分组编码
     * @param groupName 分组名称
     * @return 树形物料分组
     */
    List<Tree<Long>> getMdmMaterialGroupTree(Long parentId, String groupCode, String groupName, Boolean enabled);

    /**
     * 导入excel 表格
     *
     * @param excelVOList   物料分组列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    R importMaterialGroup(List<MdmMaterialGroupExcelVO> excelVOList, BindingResult bindingResult);

    /**
     * 导出excel 表格
     *
     * @param mdmMaterialGroup 查询条件
     * @return excel 文件流
     */
    List<MdmMaterialGroupExcelVO> export(MdmMaterialGroupEntity mdmMaterialGroup);

    /**
     * 通过id删除物料分组表
     *
     * @param ids id列表
     * @return R
     */
    Boolean removeMaterialGroup(ArrayList<Long> ids);
}
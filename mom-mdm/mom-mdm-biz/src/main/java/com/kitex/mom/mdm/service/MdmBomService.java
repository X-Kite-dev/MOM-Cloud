package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.yulichang.extension.mapping.base.MPJDeepService;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.dto.MdmBomChildDTO;
import com.kitex.mom.mdm.dto.MdmBomDTO;
import com.kitex.mom.mdm.entity.MdmBomChildEntity;
import com.kitex.mom.mdm.entity.MdmBomEntity;
import com.kitex.mom.mdm.vo.MdmBomTreeVO;
import com.kitex.mom.mdm.vo.excel.MdmBomExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;
import java.util.Map;

public interface MdmBomService extends MPJDeepService<MdmBomEntity> {

    /**
     * 工程发布前只读复核结构化替代策略及当前权威认证，不修改冻结版本。
     */
    void validateEngineeringSubstitutes(MdmBomEntity bom, List<MdmBomChildEntity> children);

    /**
     * 保存 bom
     *
     * @param mdmBom
     * @return
     */
    Boolean saveDeep(MdmBomDTO mdmBom);

    /**
     * 修改 bom
     *
     * @param mdmBom
     * @return
     */

    Boolean updateDeep(MdmBomDTO mdmBom);

    /**
     * 删除 bom
     *
     * @param ids
     * @return
     */
    Boolean removeDeep(Long[] ids);

    /**
     * 删除 bom 物料
     *
     * @param ids
     * @return
     */
    Boolean removeChild(Long[] ids);

    /**
     * 根据条件查询 bom
     *
     * @param mdmBom
     * @return
     */
    MdmBomDTO getBomDeep(MdmBomEntity mdmBom);

    /**
     * 根据产品编码获取该产品所有子物料信息
     *
     * @param productCode 产品编码
     * @param version     版本
     * @return 子物料集合, 按层级展开
     */
    List<MdmBomChildDTO> getChildMaterials(String productCode, Long bomId, String version);

    /**
     * 按指定版本读取BOM维护结构。
     *
     * <p>该接口可读取尚未发布的草案和审批冻结版本，只用于主数据维护预览，
     * 不代表该BOM已经具备生产、计划或领料执行资格。</p>
     *
     * @param bomId BOM版本ID
     * @return 完整层级结构
     */
    List<MdmBomChildDTO> getMaintenanceStructure(Long bomId);

    /**
     * 导入excel 表
     *
     * @param excelVOList   对象实体列表
     * @param bindingResult 错误信息列表
     * @return ok fail
     */
    R importExcel(List<MdmBomExcelVO> excelVOList, BindingResult bindingResult);

    /**
     * 根据产品编码获取该产品子物料bom信息(一层)
     *
     * @param productCode 产品编码
     */
    List<MdmBomChildEntity> getBomChild(String productCode, String bomVersion);

    /**
     * 根据产品编码列表获取该产品所有子物料bom信息包括自己
     *
     * @param productCodes 产品编码列表
     * @return 子物料集合, 按层次区分
     */
    Map<String, MdmBomChildDTO> getAllChildMaterials(List<String> productCodes);

    /**
     * 导出excel 表格
     *
     * @param mdmBom 查询条件
     * @param ids    导出指定ID
     * @return excel 文件流
     */
    List<MdmBomExcelVO> exportExcel(MdmBomEntity mdmBom, Long[] ids);

    /**
     * BOM复制
     *
     * @param id id
     * @return R
     */
    Boolean bomCopy(Long id);

    /**
     * bom树查询
     *
     * @param queryWrapper 查询条件
     * @return
     */
    List<MdmBomTreeVO> tree(QueryWrapper<MdmBomEntity> queryWrapper);

    /**
     * 根据id设置默认bom
     *
     * @param id id
     * @return R
     */
    Boolean bomDefault(Long id);
}

package com.kitex.mom.mdm.service;

import cn.hutool.core.lang.tree.Tree;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.barcode.dto.req.PrintReq;
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.vo.excel.MdmWarehouseLocationExcelVO;
import org.springframework.validation.BindingResult;

import java.util.ArrayList;
import java.util.List;

public interface MdmWarehouseLocationService extends IService<MdmWarehouseLocationEntity> {

    List<Tree<Long>> getTreeList(MdmWarehouseLocationEntity params);

    List<Tree<Long>> getTreeAllList(MdmWarehouseLocationEntity params);

    /**
     * 删除
     * 也删除所有子节点
     *
     * @param ids
     * @return
     */
    Boolean removeTreeBatchByIds(ArrayList<Long> ids);

    /**
     * 库位打印
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    String print(PrintReq printReq);

    /**
     * 导入excel 表
     *
     * @param excelVOList   库位列表
     * @param bindingResult 错误信息列表
     * @return ok fail
     */
    R importExcel(List<MdmWarehouseLocationExcelVO> excelVOList, BindingResult bindingResult);

    /**
     * 导出excel 表格
     *
     * @param mdmWarehouseLocation 查询条件
     * @return excel 文件流
     */
    List<MdmWarehouseLocationExcelVO> exportExcel(MdmWarehouseLocationEntity mdmWarehouseLocation);
}
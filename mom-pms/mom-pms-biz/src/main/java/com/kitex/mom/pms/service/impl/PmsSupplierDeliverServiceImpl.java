package com.kitex.mom.pms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmSupplierEntity;
import com.kitex.mom.mdm.feign.RemoteSupplierService;
import com.kitex.mom.pms.dto.PmsSupplierDeliverDTO;
import com.kitex.mom.pms.entity.PmsSupplierFileEntity;
import com.kitex.mom.pms.entity.PmsSupplierMatEntity;
import com.kitex.mom.pms.mapper.PmsSupplierFileMapper;
import com.kitex.mom.pms.mapper.PmsSupplierMatMapper;
import com.kitex.mom.pms.service.PmsSupplierDeliverService;
import com.kitex.mom.pms.vo.PmsSupplierDeliverVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 供应商供货表
 *
 * @author kitex
 * @date 2025-03-20 09:32:14
 */
@Service
@AllArgsConstructor
public class PmsSupplierDeliverServiceImpl implements PmsSupplierDeliverService {

    private final RemoteSupplierService remoteSupplierService;

    private final PmsSupplierFileMapper pmsSupplierFileMapper;

    private final PmsSupplierMatMapper pmsSupplierMatMapper;

    /**
     * 通过id查询供货信息
     *
     * @param id id
     * @return R
     */
    @Override
    public PmsSupplierDeliverVO getSupplierDeliverById(Long id) {
        MdmSupplierEntity mdmSupplierEntity = new MdmSupplierEntity();
        mdmSupplierEntity.setId(id);
        R<MdmSupplierEntity> detail = remoteSupplierService.getDetails(mdmSupplierEntity);
        if (detail.getCode() != 0) {
            return null;
        }
        PmsSupplierDeliverVO pmsSupplierDeliverVO = new PmsSupplierDeliverVO();
        BeanUtils.copyProperties(detail.getData(), pmsSupplierDeliverVO);
        pmsSupplierDeliverVO.setMatList(pmsSupplierMatMapper.selectList(new LambdaQueryWrapper<PmsSupplierMatEntity>()
                .eq(PmsSupplierMatEntity::getSupplierId, id)));
        pmsSupplierDeliverVO.setFileList(pmsSupplierFileMapper.selectList(new LambdaQueryWrapper<PmsSupplierFileEntity>()
                .eq(PmsSupplierFileEntity::getSupplierId, id)));
        return pmsSupplierDeliverVO;
    }

    /**
     * 修改供应商-供货
     *
     * @param pmsSupplierDeliverDTO 供应商-供货表
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateSupplierDeliverById(PmsSupplierDeliverDTO pmsSupplierDeliverDTO) {
        // 物料保存逻辑
        pmsSupplierMatMapper.delete(new LambdaQueryWrapper<PmsSupplierMatEntity>()
                .eq(PmsSupplierMatEntity::getSupplierId, pmsSupplierDeliverDTO.getId()));
        pmsSupplierDeliverDTO.getMatList()
                .stream()
                .peek(item -> {
                    item.setId(null);
                    item.setSupplierId(pmsSupplierDeliverDTO.getId());
                }).forEach(pmsSupplierMatMapper::insert);
        // 附件保存逻辑
        pmsSupplierFileMapper.delete(new LambdaQueryWrapper<PmsSupplierFileEntity>()
                .eq(PmsSupplierFileEntity::getSupplierId, pmsSupplierDeliverDTO.getId()));
        pmsSupplierDeliverDTO.getFileList()
                .stream()
                .peek(item -> {
                    item.setId(null);
                    item.setSupplierId(pmsSupplierDeliverDTO.getId());
                }).forEach(pmsSupplierFileMapper::insert);
        return Boolean.TRUE;
    }

    /**
     * 根据供应商编码查询供货物料编码
     *
     * @param supplierCode 供应商编码
     * @return R
     */
    @Override
    public List<String> getMatCodes(String supplierCode) {
        MdmSupplierEntity mdmSupplierEntity = new MdmSupplierEntity();
        mdmSupplierEntity.setSupplierCode(supplierCode);
        R<MdmSupplierEntity> detail = remoteSupplierService.getDetails(mdmSupplierEntity);
        if (detail.getData() == null) {
            throw new CheckedException("供应商不存在");
        }
        List<PmsSupplierMatEntity> pmsSupplierMatEntities = pmsSupplierMatMapper.selectList(new LambdaQueryWrapper<PmsSupplierMatEntity>()
                .eq(PmsSupplierMatEntity::getSupplierId, detail.getData().getId()));
        if (pmsSupplierMatEntities.isEmpty()) {
            return new ArrayList<>();
        }
        return pmsSupplierMatEntities.stream().map(PmsSupplierMatEntity::getMatCode).toList();
    }
}
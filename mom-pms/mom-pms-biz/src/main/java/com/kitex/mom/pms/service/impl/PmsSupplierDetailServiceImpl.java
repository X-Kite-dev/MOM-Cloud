package com.kitex.mom.pms.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmSupplierEntity;
import com.kitex.mom.mdm.enums.SupplierCheckStatusEnum;
import com.kitex.mom.mdm.feign.RemoteSupplierService;
import com.kitex.mom.pms.dto.PmsSupplierDTO;
import com.kitex.mom.pms.entity.PmsSupplierAptitudeEntity;
import com.kitex.mom.pms.entity.PmsSupplierDetailEntity;
import com.kitex.mom.pms.mapper.PmsSupplierAptitudeMapper;
import com.kitex.mom.pms.mapper.PmsSupplierDetailMapper;
import com.kitex.mom.pms.service.PmsSupplierDetailService;
import com.kitex.mom.pms.vo.PmsSupplierVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 供应商详情表
 *
 * @author kitex
 * @date 2025-03-19 08:56:36
 */
@Service
@AllArgsConstructor
public class PmsSupplierDetailServiceImpl extends ServiceImpl<PmsSupplierDetailMapper, PmsSupplierDetailEntity> implements PmsSupplierDetailService {

    private final RemoteSupplierService remoteSupplierService;

    private final PmsSupplierAptitudeMapper pmsSupplierAptitudeMapper;

    /**
     * 分页查询
     *
     * @param page        分页对象
     * @param mdmSupplier 供应商表
     * @return
     */
    @Override
    public Page<MdmSupplierEntity> getSupplierPage(Page page, MdmSupplierEntity mdmSupplier) {
        R<Page<MdmSupplierEntity>> mdmSupplierPage = remoteSupplierService.getMdmSupplierPage(page.getCurrent(), page.getSize(), mdmSupplier);
        if (mdmSupplierPage.getCode() != 0) {
            throw new CheckedException("查询供应商失败");
        }
        return mdmSupplierPage.getData();
    }

    /**
     * 通过id查询供应商表
     *
     * @param id id
     * @return R
     */
    @Override
    public PmsSupplierVO getSupplierById(Long id) {
        MdmSupplierEntity mdmSupplierEntity = new MdmSupplierEntity();
        mdmSupplierEntity.setId(id);
        R<MdmSupplierEntity> detail = remoteSupplierService.getDetails(mdmSupplierEntity);
        if (detail.getCode() != 0) {
            return null;
        }
        PmsSupplierVO pmsSupplierVO = new PmsSupplierVO();
        BeanUtils.copyProperties(detail.getData(), pmsSupplierVO, "id");
        pmsSupplierVO.setSupplierId(id);
        // 查询供应商详情
        PmsSupplierDetailEntity detailEntity = this.getOne(new LambdaQueryWrapper<PmsSupplierDetailEntity>()
                .eq(PmsSupplierDetailEntity::getSupplierId, id));
        if (detailEntity != null) {
            BeanUtils.copyProperties(detailEntity, pmsSupplierVO, "createTime", "createBy");
        }

        // 查询供应商资质
        pmsSupplierVO.setAptitudeList(pmsSupplierAptitudeMapper
                .selectList(new LambdaQueryWrapper<PmsSupplierAptitudeEntity>().eq(PmsSupplierAptitudeEntity::getSupplierId, id)));
        return pmsSupplierVO;
    }

    /**
     * 新增供应商
     *
     * @param pmsSupplierDTO 供应商DTO
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveSupplier(PmsSupplierDTO pmsSupplierDTO) {
        MdmSupplierEntity mdmSupplierEntity = new MdmSupplierEntity();
        BeanUtils.copyProperties(pmsSupplierDTO, mdmSupplierEntity, "id");
        mdmSupplierEntity.setCheckStatus(SupplierCheckStatusEnum.NO_CHECK.getCode());
        R<Long> save = remoteSupplierService.save(mdmSupplierEntity);
        if (save.getCode() != 0) {
            throw new CheckedException("保存供应商失败");
        }
        PmsSupplierDetailEntity pmsSupplierDetailEntity = new PmsSupplierDetailEntity();
        BeanUtils.copyProperties(pmsSupplierDTO, pmsSupplierDetailEntity, "id");
        pmsSupplierDetailEntity.setSupplierId(save.getData());
        this.save(pmsSupplierDetailEntity);
        // 供应商资质保存/修改逻辑
        pmsSupplierDTO.setSupplierId(save.getData());
        aptitudeSave(pmsSupplierDTO);
        return Boolean.TRUE;
    }

    /**
     * 修改供应商表
     *
     * @param pmsSupplierDTO 供应商表
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateSupplierById(PmsSupplierDTO pmsSupplierDTO) {
        MdmSupplierEntity mdmSupplierEntity = new MdmSupplierEntity();
        BeanUtils.copyProperties(pmsSupplierDTO, mdmSupplierEntity);
        mdmSupplierEntity.setId(pmsSupplierDTO.getSupplierId());
        R<Boolean> booleanR = remoteSupplierService.updateById(mdmSupplierEntity);
        if (!booleanR.getData()) {
            throw new CheckedException("修改供应商失败");
        }
        PmsSupplierDetailEntity pmsSupplierDetailEntity = new PmsSupplierDetailEntity();
        BeanUtils.copyProperties(pmsSupplierDTO, pmsSupplierDetailEntity);
        this.saveOrUpdate(pmsSupplierDetailEntity);
        // 供应商资质保存/修改逻辑
        pmsSupplierAptitudeMapper.delete(new LambdaQueryWrapper<PmsSupplierAptitudeEntity>()
                .eq(PmsSupplierAptitudeEntity::getSupplierId, pmsSupplierDTO.getSupplierId()));
        aptitudeSave(pmsSupplierDTO);
        return Boolean.TRUE;
    }

    /**
     * 批量保存或更新供应商资质
     *
     * @param pmsSupplierDTO
     */
    private void aptitudeSave(PmsSupplierDTO pmsSupplierDTO) {
        pmsSupplierDTO.getAptitudeList()
                .stream()
                .peek(item -> {
                    item.setId(null);
                    item.setSupplierId(pmsSupplierDTO.getSupplierId());
                }).forEach(pmsSupplierAptitudeMapper::insert);
    }

    /**
     * 通过id删除供应商详情表
     *
     * @param ids id列表
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeBatchSupplier(Long[] ids) {
        R<Boolean> booleanR = remoteSupplierService.removeById(ids);
        if (!booleanR.getData()) {
            throw new CheckedException("删除供应商失败");
        }
        // 删除供应商资质信息
        pmsSupplierAptitudeMapper.delete(new LambdaQueryWrapper<PmsSupplierAptitudeEntity>()
                .in(PmsSupplierAptitudeEntity::getSupplierId, CollUtil.toList(ids)));
        return this.remove(new LambdaQueryWrapper<PmsSupplierDetailEntity>().in(PmsSupplierDetailEntity::getSupplierId, CollUtil.toList(ids)));
    }
}
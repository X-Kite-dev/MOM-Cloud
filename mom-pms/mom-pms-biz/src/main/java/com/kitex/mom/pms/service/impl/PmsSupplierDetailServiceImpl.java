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
 * PMS供应商准入资料编排，沿原MDM主档、待审核和资质写入流程办理。
 * PMS接口保留整数0/1启停合同，与MDM布尔状态显式双向映射，空值不推断启用或禁用。
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
	 * 查询供应商及准入资料，显式还原PMS整数启停状态，避免BeanUtils跳过布尔到整数的类型差异。
	 *
	 * @param id 原供应商主键，保持原Long身份及既有远端查询范围
	 * @return 原主档和准入资料；MDM查询失败仍返回null，未知启停状态仍为null
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
		// 查询供应商准入详情，保留原创建审计和主档身份。
		PmsSupplierDetailEntity detailEntity = this.getOne(new LambdaQueryWrapper<PmsSupplierDetailEntity>()
				.eq(PmsSupplierDetailEntity::getSupplierId, id));
		if (detailEntity != null) {
			BeanUtils.copyProperties(detailEntity, pmsSupplierVO, "createTime", "createBy");
		}
		pmsSupplierVO.setStatus(toPmsStatus(detail.getData().getStatus()));

		// 查询供应商资质。
		pmsSupplierVO.setAptitudeList(pmsSupplierAptitudeMapper
				.selectList(new LambdaQueryWrapper<PmsSupplierAptitudeEntity>()
						.eq(PmsSupplierAptitudeEntity::getSupplierId, id)));
		return pmsSupplierVO;
	}

	/**
	 * 新增供应商；任何写入前校验整数启停值，再显式映射MDM布尔值，保留原待审核入口。
	 *
	 * @param pmsSupplierDTO 原供应商资料，status仅接受0、1或原合同允许的null
	 * @return 原主档、详情及资质写入流程成功返回true
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean saveSupplier(PmsSupplierDTO pmsSupplierDTO) {
		PmsSupplierMaintenanceValidator.validate(pmsSupplierDTO);
		Boolean status = toMdmStatus(pmsSupplierDTO.getStatus());
		MdmSupplierEntity mdmSupplierEntity = new MdmSupplierEntity();
		BeanUtils.copyProperties(pmsSupplierDTO, mdmSupplierEntity, "id");
		mdmSupplierEntity.setStatus(status);
		mdmSupplierEntity.setCheckStatus(SupplierCheckStatusEnum.NO_CHECK.getCode());
		R<Long> save = remoteSupplierService.save(mdmSupplierEntity);
		if (save.getCode() != 0) {
			throw new CheckedException("保存供应商失败");
		}
		PmsSupplierDetailEntity pmsSupplierDetailEntity = new PmsSupplierDetailEntity();
		BeanUtils.copyProperties(pmsSupplierDTO, pmsSupplierDetailEntity, "id");
		pmsSupplierDetailEntity.setSupplierId(save.getData());
		this.save(pmsSupplierDetailEntity);
		// 供应商资质保存/修改逻辑。
		pmsSupplierDTO.setSupplierId(save.getData());
		aptitudeSave(pmsSupplierDTO);
		return Boolean.TRUE;
	}

	/**
	 * 修改供应商；非法启停值在远端及本地写入前拒绝，null保留原更新选择语义，不补造默认状态。
	 *
	 * @param pmsSupplierDTO 原供应商和准入资料，主键、审核及组织字段继续沿原合同传递
	 * @return 原主档、详情及资质修改流程成功返回true
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Boolean updateSupplierById(PmsSupplierDTO pmsSupplierDTO) {
		PmsSupplierMaintenanceValidator.validate(pmsSupplierDTO);
		Boolean status = toMdmStatus(pmsSupplierDTO.getStatus());
		MdmSupplierEntity mdmSupplierEntity = new MdmSupplierEntity();
		BeanUtils.copyProperties(pmsSupplierDTO, mdmSupplierEntity);
		mdmSupplierEntity.setStatus(status);
		mdmSupplierEntity.setId(pmsSupplierDTO.getSupplierId());
		R<Boolean> booleanR = remoteSupplierService.updateById(mdmSupplierEntity);
		if (!booleanR.getData()) {
			throw new CheckedException("修改供应商失败");
		}
		PmsSupplierDetailEntity pmsSupplierDetailEntity = new PmsSupplierDetailEntity();
		BeanUtils.copyProperties(pmsSupplierDTO, pmsSupplierDetailEntity);
		this.saveOrUpdate(pmsSupplierDetailEntity);
		// 供应商资质保存/修改逻辑。
		pmsSupplierAptitudeMapper.delete(new LambdaQueryWrapper<PmsSupplierAptitudeEntity>()
				.eq(PmsSupplierAptitudeEntity::getSupplierId, pmsSupplierDTO.getSupplierId()));
		aptitudeSave(pmsSupplierDTO);
		return Boolean.TRUE;
	}

	/** PMS既有0/1合同只在本服务转换；空值保持未知，非法整数在任何写入前拒绝。 */
	private static Boolean toMdmStatus(Integer status) {
		if (status == null) {
			return null;
		}
		if (status != 0 && status != 1) {
			throw new CheckedException("供应商状态只能为0或1");
		}
		return status == 1;
	}

	/** MDM布尔状态回到PMS整数合同，不能将原主档的null伪装成禁用。 */
	private static Integer toPmsStatus(Boolean status) {
		if (status == null) {
			return null;
		}
		return status ? 1 : 0;
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

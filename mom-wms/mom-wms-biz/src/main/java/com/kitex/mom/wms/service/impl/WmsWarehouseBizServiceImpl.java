package com.kitex.mom.wms.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.wms.entity.WmsWarehouseBizEntity;
import com.kitex.mom.wms.mapper.WmsWarehouseBizMapper;
import com.kitex.mom.wms.service.WmsWarehouseBizService;
import com.kitex.mom.wms.vo.WmsWarehouseBizCheckVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 仓库业务配置
 *
 * @author kitex
 * @date 2025-01-13 17:48:14
 */
@Service
public class WmsWarehouseBizServiceImpl extends ServiceImpl<WmsWarehouseBizMapper, WmsWarehouseBizEntity>
		implements WmsWarehouseBizService {

	@Override
	@Transactional(rollbackFor = Exception.class)
	public boolean updateAll(List<WmsWarehouseBizCheckVO> list) {
		/* 获取当前工厂编码 */
		String factoryCode = FactoryContextHolder.getFactoryCode();
		if (StrUtil.isBlank(factoryCode) || TenantContextHolder.getTenantId() == null || list == null
				|| Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
				|| Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
			throw new CheckedException("仓库业务配置缺少组织或配置快照");
		}
		Set<String> codes = new HashSet<>();
		for (WmsWarehouseBizCheckVO command : list) {
			if (command == null || StrUtil.isBlank(command.getWarehouseCode()) || command.getBizs() == null
					|| !codes.add(command.getWarehouseCode())) {
				throw new CheckedException("仓库配置快照无效或重复");
			}
		}
		// 停用仓库不在启用仓库快照中，绝不能因为空集合扩大删除范围。
		if (codes.isEmpty()) {
			return true;
		}
		var scope = Wrappers.<WmsWarehouseBizEntity>lambdaQuery()
				.eq(WmsWarehouseBizEntity::getFactoryCode, factoryCode)
				.eq(WmsWarehouseBizEntity::getTenantId, TenantContextHolder.getTenantId())
				.in(WmsWarehouseBizEntity::getWarehouseCode, codes);
		Long count = baseMapper.selectCount(scope);
		if (count == null || baseMapper.delete(scope) != count.intValue()) {
			throw new CheckedException("替换仓库业务配置失败");
		}
		// 2. 重新添加选择项
		List<WmsWarehouseBizEntity> addList = new ArrayList<>();
		for (WmsWarehouseBizCheckVO checkVO : list) {
			List<String> values = checkVO.getBizs().isEmpty() ? List.of(NO_BUSINESS) : checkVO.getBizs();
			for (String biz : values) {
				WmsWarehouseBizEntity bizEntity = new WmsWarehouseBizEntity();
				bizEntity.setWarehouseCode(checkVO.getWarehouseCode())
						.setBizType(biz)
						.setFactoryCode(factoryCode);
				bizEntity.setTenantId(TenantContextHolder.getTenantId());
				addList.add(bizEntity);
			}
		}
		for (WmsWarehouseBizEntity row : addList) {
			if (baseMapper.insert(row) != 1) {
				throw new CheckedException("保存仓库业务配置失败");
			}
		}
		return true;
	}
}

package com.kitex.mom.iot.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.iot.dto.IotProductDTO;
import com.kitex.mom.iot.entity.IotProductEntity;
import com.kitex.mom.iot.mapper.IotProductMapper;
import com.kitex.mom.iot.service.IotProductModelService;
import com.kitex.mom.iot.service.IotProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IoT产品服务实现
 *
 * @author system
 * @date 2025-03-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotProductServiceImpl extends ServiceImpl<IotProductMapper, IotProductEntity> implements IotProductService {

    private final IotProductModelService iotProductModelService;

    @Override
    public IPage<IotProductEntity> getProductPage(Page<IotProductEntity> page, IotProductEntity product) {
        return baseMapper.selectProductPage(page, product);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createProduct(IotProductDTO productDTO) {
        IotProductEntity product = new IotProductEntity();
        BeanUtil.copyProperties(productDTO, product);
        return this.save(product);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateProduct(IotProductDTO productDTO) {
        IotProductEntity product = this.getById(productDTO.getId());
        if (product == null) {
            return false;
        }
        BeanUtil.copyProperties(productDTO, product);
        return this.updateById(product);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeProduct(Long id) {
        // 先删除产品关联的数据模型
        iotProductModelService.removeByProductId(id);
        // 再删除产品
        return this.removeById(id);
    }
} 
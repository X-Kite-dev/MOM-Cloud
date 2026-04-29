package com.kitex.mom.iot.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.iot.dto.IotProductModelDTO;
import com.kitex.mom.iot.entity.IotProductModelEntity;
import com.kitex.mom.iot.mapper.IotProductModelMapper;
import com.kitex.mom.iot.service.IotProductModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * IoT产品数据模型服务实现
 *
 * @author system
 * @date 2025-03-12
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotProductModelServiceImpl extends ServiceImpl<IotProductModelMapper, IotProductModelEntity> implements IotProductModelService {

    @Override
    public IPage<IotProductModelEntity> getModelPage(Page<IotProductModelEntity> page, IotProductModelEntity model) {
        return baseMapper.selectModelPage(page, model);
    }

    @Override
    public List<IotProductModelEntity> getModelsByProductId(Long productId) {
        return baseMapper.selectByProductId(productId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createModel(IotProductModelDTO modelDTO) {
        // 检查标识符是否已存在
        LambdaQueryWrapper<IotProductModelEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(IotProductModelEntity::getProductId, modelDTO.getProductId())
                .eq(IotProductModelEntity::getIdentifier, modelDTO.getIdentifier());
        if (this.count(queryWrapper) > 0) {
            log.error("标识符已存在: {}", modelDTO.getIdentifier());
            return false;
        }

        IotProductModelEntity model = new IotProductModelEntity();
        BeanUtil.copyProperties(modelDTO, model);
        return this.save(model);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean batchCreateModel(List<IotProductModelDTO> modelDTOList) {
        if (CollUtil.isEmpty(modelDTOList)) {
            return false;
        }

        List<IotProductModelEntity> modelList = new ArrayList<>(modelDTOList.size());
        for (IotProductModelDTO modelDTO : modelDTOList) {
            IotProductModelEntity model = new IotProductModelEntity();
            BeanUtil.copyProperties(modelDTO, model);
            modelList.add(model);
        }
        return this.saveBatch(modelList);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateModel(IotProductModelDTO modelDTO) {
        IotProductModelEntity model = this.getById(modelDTO.getId());
        if (model == null) {
            return false;
        }

        // 检查标识符是否已被其他模型使用
        if (!model.getIdentifier().equals(modelDTO.getIdentifier())) {
            LambdaQueryWrapper<IotProductModelEntity> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(IotProductModelEntity::getProductId, modelDTO.getProductId())
                    .eq(IotProductModelEntity::getIdentifier, modelDTO.getIdentifier())
                    .ne(IotProductModelEntity::getId, modelDTO.getId());
            if (this.count(queryWrapper) > 0) {
                log.error("标识符已存在: {}", modelDTO.getIdentifier());
                return false;
            }
        }

        BeanUtil.copyProperties(modelDTO, model);
        return this.updateById(model);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeModel(Long id) {
        return this.removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeByProductId(Long productId) {
        LambdaQueryWrapper<IotProductModelEntity> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(IotProductModelEntity::getProductId, productId);
        return this.remove(queryWrapper);
    }
} 
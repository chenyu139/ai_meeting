package com.gczm.aimeeting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gczm.aimeeting.entity.IdempotencyRecordEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IdempotencyRecordMapper extends BaseMapper<IdempotencyRecordEntity> {
}

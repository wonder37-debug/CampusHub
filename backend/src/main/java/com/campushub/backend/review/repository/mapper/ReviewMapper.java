package com.campushub.backend.review.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campushub.backend.review.repository.entity.ReviewEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * ord_review 表的 MyBatis-Plus Mapper。
 *
 * <p>仅提供数据访问能力；评价防重由 {@code uk_review_order_author} 唯一索引保证，
 * 信用分计算由 Service 层在内存中完成。</p>
 */
@Mapper
public interface ReviewMapper extends BaseMapper<ReviewEntity> {
}
package com.campushub.backend.upload.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campushub.backend.upload.repository.entity.UploadedAssetEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UploadedAssetRepository extends BaseMapper<UploadedAssetEntity> {

    @Select("SELECT * FROM uploaded_asset WHERE filename = #{filename}")
    UploadedAssetEntity findByFilename(@Param("filename") String filename);
}

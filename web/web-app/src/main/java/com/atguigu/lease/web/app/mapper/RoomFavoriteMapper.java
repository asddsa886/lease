package com.atguigu.lease.web.app.mapper;

import com.atguigu.lease.model.entity.RoomFavorite;
import com.atguigu.lease.web.app.vo.favorite.RoomFavoriteItemVo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface RoomFavoriteMapper extends BaseMapper<RoomFavorite> {

    IPage<RoomFavoriteItemVo> pageItem(Page<RoomFavoriteItemVo> page, @Param("userId") Long userId);

    List<Long> selectFavoriteRoomIds(@Param("userId") Long userId, @Param("roomIds") List<Long> roomIds);
}

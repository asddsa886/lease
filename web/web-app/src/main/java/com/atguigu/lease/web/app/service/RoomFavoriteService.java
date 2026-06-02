package com.atguigu.lease.web.app.service;

import com.atguigu.lease.model.entity.RoomFavorite;
import com.atguigu.lease.web.app.vo.favorite.RoomFavoriteItemVo;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Set;

public interface RoomFavoriteService extends IService<RoomFavorite> {

    void saveFavorite(Long roomId);

    void saveFavorite(Long userId, Long roomId);

    void removeFavorite(Long roomId);

    void removeFavorite(Long userId, Long roomId);

    IPage<RoomFavoriteItemVo> pageItem(Page<RoomFavoriteItemVo> page);

    IPage<RoomFavoriteItemVo> pageItem(Page<RoomFavoriteItemVo> page, Long userId);

    boolean isFavorite(Long userId, Long roomId);

    Set<Long> favoriteRoomIds(Long userId, List<Long> roomIds);
}

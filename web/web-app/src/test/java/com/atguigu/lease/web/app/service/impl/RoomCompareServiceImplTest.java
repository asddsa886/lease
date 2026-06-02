package com.atguigu.lease.web.app.service.impl;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.web.app.service.RoomInfoService;
import com.atguigu.lease.web.app.vo.apartment.ApartmentItemVo;
import com.atguigu.lease.web.app.vo.compare.RoomCompareVo;
import com.atguigu.lease.web.app.vo.room.RoomDetailVo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoomCompareServiceImplTest {

    private final RoomInfoService roomInfoService = mock(RoomInfoService.class);
    private final RoomCompareServiceImpl service = new RoomCompareServiceImpl(roomInfoService);

    @Test
    void shouldDeduplicateRoomIdsAndKeepRequestOrder() {
        when(roomInfoService.getDetailById(3L)).thenReturn(room(3L, "A301", "3600", "朝阳公寓"));
        when(roomInfoService.getDetailById(2L)).thenReturn(room(2L, "A201", "3200", "朝阳公寓"));

        RoomCompareVo result = service.compareRooms(List.of(3L, 2L, 3L));

        assertThat(result.getItems()).extracting("roomId").containsExactly(3L, 2L);
        assertThat(result.getDifferenceFields()).contains("rent", "roomNumber");
    }

    @Test
    void shouldRejectEmptyCompareRequest() {
        assertThatThrownBy(() -> service.compareRooms(List.of()))
                .isInstanceOf(LeaseException.class);
    }

    private RoomDetailVo room(Long id, String roomNumber, String rent, String apartmentName) {
        RoomDetailVo detailVo = new RoomDetailVo();
        detailVo.setId(id);
        detailVo.setRoomNumber(roomNumber);
        detailVo.setRent(new BigDecimal(rent));

        ApartmentItemVo apartmentItemVo = new ApartmentItemVo();
        apartmentItemVo.setId(9L);
        apartmentItemVo.setName(apartmentName);
        detailVo.setApartmentItemVo(apartmentItemVo);
        return detailVo;
    }
}

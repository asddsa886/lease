package com.atguigu.lease.web.admin.service.impl;

import com.atguigu.lease.web.admin.mapper.RoomMetricsMapper;
import com.atguigu.lease.web.admin.vo.metrics.RoomFunnelVo;
import com.atguigu.lease.web.admin.vo.metrics.RoomQualityScoreVo;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoomMetricsServiceImplTest {

    private final RoomMetricsMapper roomMetricsMapper = mock(RoomMetricsMapper.class);
    private final RoomMetricsServiceImpl service = new RoomMetricsServiceImpl(roomMetricsMapper);

    @Test
    void shouldCalculateQualityScoreFromContentAndConversionFacts() {
        Page<RoomQualityScoreVo> page = new Page<>(1, 10);
        RoomQualityScoreVo raw = new RoomQualityScoreVo();
        raw.setRoomId(3L);
        raw.setRoomNumber("A301");
        raw.setGraphCount(2L);
        raw.setAttrCount(2L);
        raw.setFacilityCount(4L);
        raw.setLabelCount(2L);
        raw.setPaymentTypeCount(1L);
        raw.setLeaseTermCount(1L);
        raw.setBrowseCount(20L);
        raw.setFavoriteCount(4L);
        raw.setAppointmentCount(2L);
        raw.setOrderCount(1L);
        page.setRecords(List.of(raw));
        when(roomMetricsMapper.pageQualityScore(page, null)).thenReturn(page);

        IPage<RoomQualityScoreVo> result = service.pageQualityScore(page, null);

        RoomQualityScoreVo scored = result.getRecords().get(0);
        assertThat(scored.getContentScore()).isGreaterThan(0);
        assertThat(scored.getConversionScore()).isGreaterThan(0);
        assertThat(scored.getQualityScore()).isBetween(1, 100);
    }

    @Test
    void shouldReturnFunnelTotalsWithConversionRates() {
        RoomFunnelVo raw = new RoomFunnelVo();
        raw.setBrowseCount(100L);
        raw.setFavoriteCount(25L);
        raw.setAppointmentCount(10L);
        raw.setOrderCount(5L);
        when(roomMetricsMapper.selectFunnel(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), 9L))
                .thenReturn(raw);

        RoomFunnelVo result = service.getFunnel(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), 9L);

        assertThat(result.getFavoriteRate()).isEqualByComparingTo("0.2500");
        assertThat(result.getAppointmentRate()).isEqualByComparingTo("0.1000");
        assertThat(result.getOrderRate()).isEqualByComparingTo("0.0500");
    }
}

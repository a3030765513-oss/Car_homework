package com.substation.common.model;

import java.util.List;
import java.util.Map;

public record SimulationState(
        int tick,
        int explorationRate,
        Map<String, String> taskConfig,
        List<CarInfo> cars,
        boolean[][] mapView,
        boolean[][] mapBlock) {

    public record CarInfo(
            String carId,
            int number,
            Point position,
            Point target,
            List<Point> routeList,
            CarStatus status,
            int steps) {}
}

package com.substation.strategysupervisor;

import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 多车路线重合检测器：当本车路线与其他车路线高度重合时触发重分配。
 */
final class RouteOverlapEvaluator {

    /** 触发重分配的重合率阈值：本车路线中超过此比例的格子出现在其他车路线中 */
    private static final double OVERLAP_THRESHOLD = 0.5;

    /**
     * 检测本车路线是否与其他车高度重合。
     *
     * @return true表示需要重分配
     */
    boolean isHighlyOverlapped(String carId, List<Point> myRoute, BlackboardClient bb) {
        if (myRoute.isEmpty()) {
            return false;
        }

        Set<String> myCells = toCellSet(myRoute);

        for (String otherId : bb.discoverCarIds()) {
            if (otherId.equals(carId)) {
                continue;
            }
            List<Point> otherRoute = bb.getCarRoute(otherId);
            if (otherRoute.isEmpty()) {
                continue;
            }

            int shared = countShared(myCells, otherRoute);
            int minLen = Math.min(myRoute.size(), otherRoute.size());
            if ((double) shared / minLen >= OVERLAP_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> toCellSet(List<Point> route) {
        Set<String> set = new HashSet<>(route.size());
        for (Point p : route) {
            set.add(p.x() + "," + p.y());
        }
        return set;
    }

    private static int countShared(Set<String> myCells, List<Point> otherRoute) {
        int count = 0;
        for (Point p : otherRoute) {
            if (myCells.contains(p.x() + "," + p.y())) {
                count++;
            }
        }
        return count;
    }
}

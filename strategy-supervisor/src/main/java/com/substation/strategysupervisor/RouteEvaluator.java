package com.substation.strategysupervisor;

import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import java.util.List;

/**
 * 路线评估器：判断当前路径是否需要策略优化。
 * 统计路径中已探索格子的比例，超过阈值则标记为需要优化。
 */
final class RouteEvaluator {

    /** 触发优化的已探索比例阈值 */
    private static final double EXPLORED_RATIO_THRESHOLD = 0.3;

    Result evaluate(BlackboardClient bb, List<Point> route) {
        if (route.isEmpty()) {
            return Result.SKIP;
        }
        int total = route.size();
        int exploredCount = 0;
        for (Point p : route) {
            if (bb.getMapViewBit(p.y(), p.x())) {
                exploredCount++;
            }
        }
        if ((double) exploredCount / total >= EXPLORED_RATIO_THRESHOLD) {
            return Result.NEED_OPTIMIZE;
        }
        return Result.SKIP;
    }

    enum Result {
        NEED_OPTIMIZE,
        SKIP
    }
}

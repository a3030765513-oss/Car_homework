package com.substation.targetplanner;

import com.substation.common.model.Point;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;

/** BFS 最短路径估算，供目标分配时评估路径长度与重合度 */
final class TargetPathEstimator {

    private static final int[][] DIRECTIONS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

    List<Point> planPath(Point start, Point target, boolean[][] blocked, int width, int height) {
        if (isOutOfBounds(target, width, height)) {
            return List.of();
        }
        if (start.equals(target)) {
            return List.of();
        }

        boolean[][] visited = copyBlocked(blocked, width, height);
        Point[][] parent = new Point[height][width];
        Queue<Point> queue = new ArrayDeque<>();

        visited[start.y()][start.x()] = true;
        queue.add(start);

        while (!queue.isEmpty()) {
            Point current = queue.poll();
            if (current.equals(target)) {
                return reconstructPath(parent, start, target);
            }
            expandNeighbors(current, visited, parent, queue, width, height);
        }
        return List.of();
    }

    private boolean[][] copyBlocked(boolean[][] blocked, int width, int height) {
        boolean[][] copy = new boolean[height][width];
        for (int row = 0; row < height; row++) {
            System.arraycopy(blocked[row], 0, copy[row], 0, width);
        }
        return copy;
    }

    private void expandNeighbors(Point current, boolean[][] visited, Point[][] parent,
                                  Queue<Point> queue, int width, int height) {
        for (int[] dir : DIRECTIONS) {
            int nx = current.x() + dir[0];
            int ny = current.y() + dir[1];
            if (!isInBounds(nx, ny, width, height) || visited[ny][nx]) {
                continue;
            }
            visited[ny][nx] = true;
            parent[ny][nx] = current;
            queue.add(new Point(nx, ny));
        }
    }

    private List<Point> reconstructPath(Point[][] parent, Point start, Point target) {
        List<Point> path = new ArrayList<>();
        Point current = target;
        while (current != null && !current.equals(start)) {
            path.add(current);
            current = parent[current.y()][current.x()];
        }
        Collections.reverse(path);
        return path;
    }

    private boolean isInBounds(int x, int y, int width, int height) {
        return x >= 0 && x < width && y >= 0 && y < height;
    }

    private boolean isOutOfBounds(Point point, int width, int height) {
        return point.x() < 0 || point.x() >= width || point.y() < 0 || point.y() >= height;
    }
}

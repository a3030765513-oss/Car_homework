package com.substation.navigator;

import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;

final class BfsPathFinder implements PathPlanner {

    private static final int[][] DIRECTIONS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

    @Override
    public List<Point> plan(Point start, Point target, BlackboardClient bb) {
        int width = bb.getMapWidth();
        int height = bb.getMapHeight();

        if (isOutOfBounds(target, width, height)) {
            return List.of();
        }

        boolean[][] visited = new boolean[height][width];
        Point[][] parent = new Point[height][width];
        Queue<Point> queue = new ArrayDeque<>();

        visited[start.y()][start.x()] = true;
        queue.add(start);

        while (!queue.isEmpty()) {
            Point current = queue.poll();

            if (current.equals(target)) {
                return reconstructPath(parent, start, target);
            }

            expandNeighbors(current, visited, parent, queue, bb, width, height);
        }
        return List.of();
    }

    private void expandNeighbors(Point current, boolean[][] visited, Point[][] parent,
                                  Queue<Point> queue, BlackboardClient bb,
                                  int width, int height) {
        for (int[] dir : DIRECTIONS) {
            int nx = current.x() + dir[0];
            int ny = current.y() + dir[1];

            if (!isInBounds(nx, ny, width, height)
                || visited[ny][nx]
                || bb.isBlocked(ny, nx)) {
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
        return Collections.unmodifiableList(path);
    }

    private boolean isInBounds(int x, int y, int width, int height) {
        return x >= 0 && x < width && y >= 0 && y < height;
    }

    private boolean isOutOfBounds(Point p, int width, int height) {
        return p.x() < 0 || p.x() >= width || p.y() < 0 || p.y() >= height;
    }
}

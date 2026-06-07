package com.substation.navigator;

import com.substation.common.model.Point;
import com.substation.common.redis.BlackboardClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

final class AStarPathFinder implements PathPlanner {

    private static final int[][] DIRECTIONS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
    private static final int INF = Integer.MAX_VALUE;

    @Override
    public List<Point> plan(Point start, Point target, BlackboardClient bb) {
        int width = bb.getMapWidth();
        int height = bb.getMapHeight();

        if (isOutOfBounds(target, width, height)) {
            return List.of();
        }

        Point[][] parent = new Point[height][width];
        int[][] gScore = createGScoreMatrix(height, width);

        gScore[start.y()][start.x()] = 0;
        PriorityQueue<Node> openSet = new PriorityQueue<>(
            Comparator.comparingInt(n -> n.fScore));
        openSet.add(new Node(start, heuristic(start, target)));

        while (!openSet.isEmpty()) {
            Node current = openSet.poll();

            if (current.point.equals(target)) {
                return reconstructPath(parent, start, target);
            }

            expandNode(current, gScore, parent, openSet, bb, width, height, target);
        }
        return List.of();
    }

    private int[][] createGScoreMatrix(int height, int width) {
        int[][] matrix = new int[height][width];
        for (int[] row : matrix) {
            Arrays.fill(row, INF);
        }
        return matrix;
    }

    private void expandNode(Node current, int[][] gScore, Point[][] parent,
                             PriorityQueue<Node> openSet, BlackboardClient bb,
                             int width, int height, Point target) {
        Point pos = current.point;
        for (int[] dir : DIRECTIONS) {
            int nx = pos.x() + dir[0];
            int ny = pos.y() + dir[1];

            if (!isInBounds(nx, ny, width, height) || bb.isBlocked(ny, nx)) {
                continue;
            }

            int tentativeG = gScore[pos.y()][pos.x()] + 1;
            if (tentativeG < gScore[ny][nx]) {
                gScore[ny][nx] = tentativeG;
                parent[ny][nx] = pos;
                Point neighbor = new Point(nx, ny);
                int fScore = tentativeG + heuristic(neighbor, target);
                openSet.add(new Node(neighbor, fScore));
            }
        }
    }

    private int heuristic(Point a, Point b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y());
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

    private record Node(Point point, int fScore) {}
}

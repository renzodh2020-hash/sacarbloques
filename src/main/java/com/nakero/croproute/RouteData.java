package com.nakero.croproute;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class RouteData {
    public String id = UUID.randomUUID().toString();
    public String name = "Ruta";
    public String dimension = "";
    public long createdAt = System.currentTimeMillis();
    public List<RoutePoint> points = new ArrayList<>();

    public RouteData() {
    }

    public RouteData(String name, String dimension, List<RoutePoint> points) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.dimension = dimension;
        this.createdAt = System.currentTimeMillis();
        this.points = new ArrayList<>(points);
    }

    public double approximateLength() {
        if (points == null || points.size() < 2) {
            return 0.0D;
        }

        double length = 0.0D;
        for (int i = 1; i < points.size(); i++) {
            RoutePoint a = points.get(i - 1);
            RoutePoint b = points.get(i);
            double dx = b.x - a.x;
            double dy = b.y - a.y;
            double dz = b.z - a.z;
            length += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        // La reproducción es en bucle: también contamos el cierre.
        RoutePoint first = points.get(0);
        RoutePoint last = points.get(points.size() - 1);
        double dx = first.x - last.x;
        double dy = first.y - last.y;
        double dz = first.z - last.z;
        length += Math.sqrt(dx * dx + dy * dy + dz * dz);

        return length;
    }
}

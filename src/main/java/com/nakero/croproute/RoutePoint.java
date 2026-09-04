package com.nakero.croproute;

public class RoutePoint {
    public double x;
    public double y;
    public double z;

    public RoutePoint() {
    }

    public RoutePoint(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double distanceSquaredXZ(double px, double pz) {
        double dx = x - px;
        double dz = z - pz;
        return dx * dx + dz * dz;
    }
}

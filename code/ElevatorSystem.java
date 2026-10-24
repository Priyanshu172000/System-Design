import java.util.*;

// Chapter 25 - Elevator system: cars with LOOK scheduling (keep going while
// requests lie ahead, turn around at the last one), and a pluggable strategy
// that picks which car answers a hall call. Discrete-time simulation.
// Run: java ElevatorSystem.java
public class ElevatorSystem {
    public static void main(String[] args) {
        ElevatorController ctrl = new ElevatorController(
                List.of(new ElevatorCar(1, 0), new ElevatorCar(2, 8)),
                new NearestSuitableCarStrategy(10));

        ctrl.hallCall(3, Direction.UP);      // someone on 3 wants to go up
        ctrl.hallCall(6, Direction.DOWN);    // someone on 6 wants to go down
        for (int tick = 1; tick <= 12; tick++) {
            if (tick == 4) ctrl.carCall(1, 9);   // the rider who boarded at 3 presses 9
            if (tick == 5) ctrl.carCall(2, 0);   // the rider who boarded at 6 presses 0
            if (tick == 6) ctrl.hallCall(5, Direction.UP);
            System.out.printf("t=%-2d  %s%n", tick, ctrl.tick());
        }
    }
}

enum Direction {
    UP, DOWN, IDLE;
    Direction opposite() { return this == UP ? DOWN : this == DOWN ? UP : IDLE; }
}

class ElevatorCar {
    private final int id;
    private int floor;
    private Direction dir = Direction.IDLE;
    private final TreeSet<Integer> upStops = new TreeSet<>();     // served while going up
    private final TreeSet<Integer> downStops = new TreeSet<>();   // served while going down

    ElevatorCar(int id, int startFloor) {
        this.id = id;
        this.floor = startFloor;
    }

    int id() { return id; }
    int floor() { return floor; }
    Direction direction() { return dir; }

    void addHallStop(int f, Direction wanted) {
        (wanted == Direction.UP ? upStops : downStops).add(f);
    }

    void addCarStop(int f) {                       // pressed inside the car
        if (f > floor) upStops.add(f);
        else if (f < floor) downStops.add(f);
    }

    private boolean hasWork() { return !upStops.isEmpty() || !downStops.isEmpty(); }

    // LOOK: next stop in the current direction; if none, the furthest
    // opposite-direction call that still lies ahead; else nothing (turn around).
    private Integer nextTarget() {
        if (dir == Direction.UP) {
            Integer a = upStops.ceiling(floor);
            if (a != null) return a;
            if (!downStops.isEmpty() && downStops.last() >= floor) return downStops.last();
        } else if (dir == Direction.DOWN) {
            Integer a = downStops.floor(floor);
            if (a != null) return a;
            if (!upStops.isEmpty() && upStops.first() <= floor) return upStops.first();
        }
        return null;
    }

    // Advance one time unit: move one floor (or open doors). Returns a short status.
    String step() {
        if (!hasWork()) {
            dir = Direction.IDLE;
            return "car" + id + "@" + floor + " idle";
        }
        if (dir == Direction.IDLE) {
            int nearest = nearestStop();
            dir = nearest >= floor ? Direction.UP : Direction.DOWN;
        }
        Integer target = nextTarget();
        if (target == null) {                       // nothing ahead: reverse (the "LOOK" turn)
            dir = dir.opposite();
            target = nextTarget();
        }
        if (target != floor) floor += target > floor ? 1 : -1;

        boolean open;
        if (floor == target) {                      // destination: serve everyone waiting here
            open = upStops.remove(floor) | downStops.remove(floor);
        } else {                                    // passing through: serve same-direction only
            open = (dir == Direction.UP ? upStops : downStops).remove(floor);
        }
        return "car" + id + "@" + floor + " " + dir + (open ? " OPEN" : "");
    }

    private int nearestStop() {
        int best = -1;
        for (TreeSet<Integer> set : List.of(upStops, downStops))
            for (int f : set)
                if (best == -1 || Math.abs(f - floor) < Math.abs(best - floor)) best = f;
        return best;
    }
}

interface CarSelectionStrategy {
    ElevatorCar select(List<ElevatorCar> cars, int floor, Direction wanted);
}

// Cost = distance; a car moving away (or moving the other way) pays a penalty
// roughly equal to a full trip, so idle or approaching cars win.
class NearestSuitableCarStrategy implements CarSelectionStrategy {
    private final int floors;
    NearestSuitableCarStrategy(int floors) { this.floors = floors; }

    public ElevatorCar select(List<ElevatorCar> cars, int floor, Direction wanted) {
        return cars.stream().min(Comparator.comparingInt(c -> cost(c, floor, wanted))).orElseThrow();
    }

    private int cost(ElevatorCar c, int floor, Direction wanted) {
        int dist = Math.abs(c.floor() - floor);
        if (c.direction() == Direction.IDLE) return dist;
        boolean approaching = (c.direction() == Direction.UP && floor >= c.floor())
                || (c.direction() == Direction.DOWN && floor <= c.floor());
        if (approaching && c.direction() == wanted) return dist;
        return dist + 2 * floors;
    }
}

class ElevatorController {
    private final List<ElevatorCar> cars;
    private final CarSelectionStrategy strategy;

    ElevatorController(List<ElevatorCar> cars, CarSelectionStrategy strategy) {
        this.cars = List.copyOf(cars);
        this.strategy = strategy;
    }

    void hallCall(int floor, Direction wanted) {
        ElevatorCar car = strategy.select(cars, floor, wanted);
        car.addHallStop(floor, wanted);
        System.out.println("     hall call " + floor + " " + wanted + " -> car" + car.id());
    }

    void carCall(int carId, int floor) {
        cars.stream().filter(c -> c.id() == carId).findFirst().orElseThrow().addCarStop(floor);
        System.out.println("     car" + carId + " button " + floor);
    }

    String tick() {
        StringBuilder sb = new StringBuilder();
        for (ElevatorCar c : cars) sb.append(String.format("%-18s", c.step()));
        return sb.toString();
    }
}

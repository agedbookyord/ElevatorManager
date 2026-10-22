package elevatormanager.model;
/** Herencia: una solicitud interior pertenece a una cabina; una exterior lleva dirección. */
public abstract sealed class Request permits Request.Internal, Request.External {
    private final int floor;
    protected Request(int floor) { this.floor = floor; }
    public final int floor() { return floor; }
    public static final class Internal extends Request {
        private final int elevator;
        public Internal(int floor, int elevator) { super(floor); this.elevator = elevator; }
        public int elevator() { return elevator; }
    }
    public static final class External extends Request {
        private final Direction direction;
        public External(int floor, Direction direction) { super(floor); this.direction = java.util.Objects.requireNonNull(direction); }
        public Direction direction() { return direction; }
    }
}

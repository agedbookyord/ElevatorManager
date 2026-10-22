package elevatormanager.core;

import elevatormanager.model.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/** Todas las transiciones del modelo se protegen con lock. Nunca se invoca Swing aquí. */
public final class Simulator implements AutoCloseable {
    public enum State { RESET, RUNNING, PAUSED, RESETTING, STOPPED, CLOSED }
    public record Config(int elevators, int floors, long moveMillis, long stopMillis) {
        public Config {
            if (elevators < 1 || elevators > 16 || floors < 2 || floors > 100 || moveMillis < 1 || moveMillis > 60000 || stopMillis < 1 || stopMillis > 60000)
                throw new IllegalArgumentException("Elevadores 1–16, pisos 2–100, tiempos 1–60000 ms");
        }
    }
    public record View(int id, int floor, Direction direction, List<String> pending,
                       boolean moving, double progress) {}
    public record Snapshot(State state, List<View> elevators, int externalPending) {}
    private record Command(long generation, String text) {}
    private record Envelope(long generation, Request.External request) {}
    private final Object lock = new Object();
    private final Config config;
    private final List<Car> cars = new ArrayList<>();
    private final BlockingQueue<Envelope> external = new LinkedBlockingQueue<>();
    private final List<Request> staged = new ArrayList<>();
    private final Thread manager;
    private State state = State.RESET;
    private long generation;
    private final Logger logger;
    private final List<Handler> handlers = new ArrayList<>();
    public Simulator(Config config, Path logs) throws IOException {
        this.config = Objects.requireNonNull(config);
        Files.createDirectories(logs);
        logger = Logger.getLogger("ElevatorManager." + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        Handler console = new ConsoleHandler(); console.setFormatter(new SimpleFormatter());
        logger.addHandler(console); handlers.add(console);
        try {
            for (int i=1;i<=config.elevators();i++) {
                Logger log = Logger.getLogger(logger.getName()+".car"+i);
                FileHandler file = new FileHandler(logs.resolve("elevator-"+i+"-%u.log").toString(), true);
                file.setFormatter(new SimpleFormatter()); log.addHandler(file); handlers.add(file);
                cars.add(new Car(i,log));
            }
        } catch (IOException | RuntimeException e) { handlers.forEach(Handler::close); throw e; }
        manager = new Thread(this::dispatch, "elevator-manager");
        cars.forEach(c -> c.thread.start()); manager.start();
    }
    public Config config() { return config; }
    private void accepting() { if(state != State.RUNNING && state != State.PAUSED) throw new IllegalStateException("Sistema no admite solicitudes: " + state); }
    private void validate(Request r) {
        Objects.requireNonNull(r);
        if(r.floor()<1 || r.floor()>config.floors()) throw new IllegalArgumentException("Piso fuera de rango");
        if(r instanceof Request.Internal i && (i.elevator()<1 || i.elevator()>cars.size())) throw new IllegalArgumentException("Elevador fuera de rango");
        if(r instanceof Request.External e && ((e.floor()==1 && e.direction()==Direction.DOWN) || (e.floor()==config.floors() && e.direction()==Direction.UP))) throw new IllegalArgumentException("Dirección exterior imposible");
    }
    public void submit(Request r) { synchronized(lock) { accepting(); validate(r); submitLocked(r); lock.notifyAll(); } }
    private void submitLocked(Request r) {
        if(r instanceof Request.Internal i) send(cars.get(i.elevator()-1),"INTERNAL " + i.floor());
        else external.add(new Envelope(generation,(Request.External)r));
    }
    private void send(Car c,String text) { c.commands.add(new Command(generation,text)); c.log.info("SEND " + generation + " " + text); }
    public void start() { synchronized(lock) {
        if(state!=State.RESET && state!=State.STOPPED) throw new IllegalStateException("Use Reanudar o Reset");
        state=State.RUNNING; staged.forEach(this::submitLocked); staged.clear(); lock.notifyAll();
    } }
    public void pause() { synchronized(lock) { if(state!=State.RUNNING) throw new IllegalStateException("No está ejecutándose"); state=State.PAUSED; lock.notifyAll(); } }
    public void resume() { synchronized(lock) { if(state!=State.PAUSED) throw new IllegalStateException("No está pausado"); state=State.RUNNING; lock.notifyAll(); } }
    public void stop() { synchronized(lock) { if(state==State.CLOSED) return; if(state==State.RESETTING) throw new IllegalStateException("Reset en curso"); clear(); state=State.STOPPED; lock.notifyAll(); } }
    private void clear() {
        generation++; external.clear(); staged.clear();
        for(Car c:cars) { c.commands.clear(); c.internal.clear(); c.up.clear(); c.down.clear(); c.moving=false; c.progress=0; }
    }
    public void reset() { synchronized(lock) {
        if(state==State.CLOSED) throw new IllegalStateException("Cerrado");
        clear(); state=State.RESETTING;
        for(Car c:cars) { c.log.info("RESET " + generation); send(c,"RESET"); }
        lock.notifyAll();
    } }
    /** Importación transaccional: si falla una línea no se agrega ninguna solicitud. */
    public int importRequests(Path path) throws IOException {
        List<Request> parsed=new ArrayList<>(); int line=0;
        for(String raw:Files.readAllLines(path)) {
            line++; String text=raw.strip(); if(text.isEmpty() || text.startsWith("#")) continue;
            String[] p=text.split("\s+");
            try {
                Request r;
                if(p.length==3 && p[0].equals("INTERNAL")) r=new Request.Internal(Integer.parseInt(p[2]),Integer.parseInt(p[1]));
                else if(p.length==3 && p[0].equals("EXTERNAL")) r=new Request.External(Integer.parseInt(p[1]),Direction.valueOf(p[2]));
                else throw new IllegalArgumentException("Comando inválido");
                validate(r); parsed.add(r);
            } catch(IllegalArgumentException e) { throw new IllegalArgumentException("Línea " + line + ": " + e.getMessage(),e); }
        }
        synchronized(lock) {
            if(state!=State.RESET) throw new IllegalStateException("Importar requiere reset completado");
            staged.addAll(parsed); return parsed.size();
        }
    }
    /** Puntaje menor: distancia + carga + penalización por viaje incompatible. */
    public static int score(int floor, Direction direction, int load, boolean active, int requested, Direction wanted, int floors) {
        boolean ahead = direction==Direction.UP ? requested>=floor : requested<=floor;
        return Math.abs(floor-requested) + load*2 + (active && (!ahead || direction!=wanted) ? floors*2 : 0);
    }
    private void dispatch() {
        synchronized(lock) {
            try { while(state!=State.CLOSED) {
                if(state!=State.RUNNING || external.isEmpty()) { lock.wait(); continue; }
                Envelope e=external.poll(); if(e.generation()!=generation) continue;
                Request.External r=e.request(); Car best=null; int bestScore=Integer.MAX_VALUE;
                for(Car c:cars) {
                    c.drain();
                    int s=score(c.floor,c.direction,c.load(),c.load()>0 || c.moving,r.floor(),r.direction(),config.floors());
                    if(s<bestScore) {bestScore=s; best=c;}
                }
                send(best,"EXTERNAL " + r.floor() + " " + r.direction()); lock.notifyAll();
            } } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
    public Snapshot snapshot() { synchronized(lock) {
        List<View> views=new ArrayList<>();
        for(Car c:cars) {
            List<String> pending=new ArrayList<>();
            c.internal.forEach(f->pending.add("I:"+f)); c.up.forEach(f->pending.add("↑:"+f)); c.down.forEach(f->pending.add("↓:"+f));
            c.commands.forEach(cmd->pending.add(cmd.text()));
            views.add(new View(c.id,c.floor,c.direction,List.copyOf(pending),c.moving,c.progress));
        }
        return new Snapshot(state,List.copyOf(views),external.size()+staged.size());
    } }
    public boolean threadsAlive() { return manager.isAlive() || cars.stream().anyMatch(c->c.thread.isAlive()); }
    @Override public void close() {
        synchronized(lock) { if(state==State.CLOSED) return; clear(); state=State.CLOSED; lock.notifyAll(); }
        boolean interrupted=false;
        for(Thread t: java.util.stream.Stream.concat(java.util.stream.Stream.of(manager),cars.stream().map(c->c.thread)).toList()) {
            for(;;) { try { t.join(); break; } catch(InterruptedException e) { interrupted=true; } }
        }
        handlers.forEach(Handler::close); if(interrupted) Thread.currentThread().interrupt();
    }
    private final class Car implements Runnable {
        final int id; final Logger log; final Thread thread;
        final BlockingQueue<Command> commands=new LinkedBlockingQueue<>();
        final NavigableSet<Integer> internal=new TreeSet<>(),up=new TreeSet<>(),down=new TreeSet<>();
        int floor=1; Direction direction=Direction.UP; boolean moving; double progress;
        Car(int id,Logger log) {this.id=id;this.log=log;thread=new Thread(this,"elevator-"+id);}
        int load() {return internal.size()+up.size()+down.size()+commands.size();}
        void drain() {
            Command cmd;
            while((cmd=commands.poll())!=null) {
                if(cmd.generation()!=generation) continue;
                String[] p=cmd.text().split(" ");
                switch(p[0]) {
                    case "INTERNAL" -> internal.add(Integer.parseInt(p[1]));
                    case "EXTERNAL" -> (Direction.valueOf(p[2])==Direction.UP?up:down).add(Integer.parseInt(p[1]));
                    case "RESET" -> { direction= floor>1?Direction.DOWN:Direction.UP; }
                    default -> throw new IllegalStateException("Protocolo desconocido");
                }
                log.info("ACK " + cmd.generation() + " " + cmd.text());
            }
        }
        boolean ahead() {
            return direction==Direction.UP ? internal.higher(floor)!=null || up.higher(floor)!=null || down.higher(floor)!=null
                : internal.lower(floor)!=null || up.lower(floor)!=null || down.lower(floor)!=null;
        }
        void reverse() {direction=direction==Direction.UP?Direction.DOWN:Direction.UP;log.info("DIRECTION " + direction);}
        /** El reloj consume solo tiempo activo y suelta el monitor mientras espera. */
        boolean delay(long millis,long epoch,boolean animation) throws InterruptedException {
            long left=TimeUnit.MILLISECONDS.toNanos(millis), total=left;
            while(left>0) {
                if(epoch!=generation || state==State.CLOSED || state==State.STOPPED) return false;
                if(state==State.PAUSED) {lock.wait();continue;}
                long start=System.nanoTime();
                TimeUnit.NANOSECONDS.timedWait(lock,Math.min(left,TimeUnit.MILLISECONDS.toNanos(16)));
                // Una pausa puede despertar esta espera: el último tramo activo cuenta, la pausa no.
                left-=Math.min(left,System.nanoTime()-start);
                if(animation) progress=1-(double)left/total;
            }
            while(state==State.PAUSED && epoch==generation) lock.wait();
            return epoch==generation && state!=State.CLOSED && state!=State.STOPPED;
        }
        @Override public void run() { synchronized(lock) {
            try { while(state!=State.CLOSED) {
                if(state!=State.RUNNING && state!=State.RESETTING) {lock.wait();continue;}
                drain(); long epoch=generation;
                if(state==State.RESETTING) {
                    if(floor==1) {
                        direction=Direction.UP; moving=false;
                        if(cars.stream().allMatch(c->c.floor==1 && c.commands.isEmpty())) {cars.forEach(c->{c.direction=Direction.UP;c.moving=false;c.progress=0;});state=State.RESET;logger.info("RESET COMPLETE");lock.notifyAll();}
                        else lock.wait();
                        continue;
                    }
                    direction=Direction.DOWN;
                } else {
                    NavigableSet<Integer> matching=direction==Direction.UP?up:down;
                    boolean stop=internal.remove(floor) | matching.remove(floor);
                    if(stop) {log.info("STOP " + floor); if(!delay(config.stopMillis(),epoch,false)) continue; drain();}
                    if(load()==0) {lock.wait();continue;}
                    if(!ahead()) {
                        reverse();
                        // Reexaminar el piso actual tras invertir antes de moverse.
                        if((direction==Direction.UP?up:down).contains(floor) || internal.contains(floor)) continue;
                    }
                }
                int target=floor+(direction==Direction.UP?1:-1);
                if(target<1 || target>config.floors()) throw new IllegalStateException("Movimiento fuera de límites");
                moving=true;progress=0;log.info("MOVE " + floor + " -> " + target);
                if(delay(config.moveMillis(),epoch,true)) { floor=target;log.info("ARRIVE " + floor); }
                moving=false;progress=0;lock.notifyAll();
            } } catch(InterruptedException e) {Thread.currentThread().interrupt();}
        } }
    }
}

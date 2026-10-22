package elevatormanager;
import elevatormanager.core.Simulator;
import elevatormanager.model.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Pruebas sin dependencias: toda condición tiene timeout y toda excepción falla la ejecución. */
public final class SimulatorTest {
    private static int passed;
    private static Path logs;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static void await(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!condition.getAsBoolean()){if(System.nanoTime()>end)throw new AssertionError("Timeout");Thread.sleep(2);}
    }
    private static Simulator system(int cars,long movement) throws Exception {
        return new Simulator(new Simulator.Config(cars,8,movement,15),logs.resolve("case-"+UUID.randomUUID()));
    }
    private static int floor(Simulator s,int id){return s.snapshot().elevators().get(id-1).floor();}
    private static void idle(Simulator s) throws Exception {await(()->s.snapshot().externalPending()==0 && s.snapshot().elevators().stream().allMatch(v->v.pending().isEmpty() && !v.moving()));}
    private static void rejects(Runnable action){try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError("Debió rechazar operación");}
    private static void test(String name,Checked test) throws Exception{test.run();passed++;System.out.println("PASS "+name);}
    private interface Checked{void run() throws Exception;}
    public static void main(String[] args) throws Exception {
        logs=Files.createTempDirectory("elevator-tests-");
        test("configuración y entradas",()->{
            rejects(()->new Simulator.Config(0,8,20,20));
            try(var s=system(1,20)){rejects(()->s.submit(new Request.Internal(2,1)));s.start();rejects(()->s.submit(new Request.Internal(9,1)));rejects(()->s.submit(new Request.Internal(2,2)));rejects(()->s.submit(new Request.External(1,Direction.DOWN)));rejects(()->s.submit(new Request.External(8,Direction.UP)));}
        });
        test("puntaje: cercanía, dirección, carga",()->{
            check(Simulator.score(2,Direction.UP,0,false,3,Direction.UP,8)<Simulator.score(7,Direction.UP,0,false,3,Direction.UP,8),"distancia");
            check(Simulator.score(2,Direction.UP,1,true,4,Direction.UP,8)<Simulator.score(3,Direction.DOWN,1,true,4,Direction.UP,8),"compatibilidad");
            check(Simulator.score(2,Direction.UP,0,false,4,Direction.UP,8)<Simulator.score(2,Direction.UP,4,true,4,Direction.UP,8),"carga");
        });
        test("asignación real a cabina cercana",()->{
            try(var s=system(2,15)){s.start();s.submit(new Request.Internal(8,2));idle(s);s.submit(new Request.External(7,Direction.DOWN));idle(s);check(floor(s,2)==7 && floor(s,1)==1,"asignación incorrecta");}
        });
        test("movimiento independiente y protocolo",()->{
            try(var s=system(2,35)){s.start();s.submit(new Request.Internal(5,1));s.submit(new Request.Internal(3,2));await(()->floor(s,1)>1 && floor(s,2)>1);idle(s);check(floor(s,1)==5 && floor(s,2)==3,"destinos");}
        });
        test("recogida en camino y no inversión anticipada",()->{
            try(var s=system(1,50)){s.start();s.submit(new Request.Internal(7,1));await(()->floor(s,1)==2);s.submit(new Request.External(4,Direction.UP));s.submit(new Request.External(3,Direction.DOWN));
                boolean saw4=false;
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while(floor(s,1)<7){check(System.nanoTime()<deadline,"timeout subida");var v=s.snapshot().elevators().getFirst();check(v.direction()==Direction.UP,"inversión prematura");if(v.floor()==4 && !v.moving())saw4=true;Thread.sleep(1);}
                check(saw4,"no paró en llamada ascendente");idle(s);check(floor(s,1)==3,"no atendió llamada descendente");
            }
        });
        test("pausa y reanudación",()->{
            try(var s=system(1,120)){s.start();s.submit(new Request.Internal(5,1));await(()->s.snapshot().elevators().getFirst().moving());s.pause();var before=s.snapshot();Thread.sleep(250);var after=s.snapshot();check(floor(s,1)==before.elevators().getFirst().floor(),"se movió pausado");check(Math.abs(after.elevators().getFirst().progress()-before.elevators().getFirst().progress())<0.1,"animación avanzó pausada");s.resume();idle(s);check(floor(s,1)==5,"reanudar");}
        });
        test("reset en vuelo, cancelación y rechazo de solicitudes antiguas",()->{
            try(var s=system(3,35)){s.start();for(int i=1;i<=3;i++)s.submit(new Request.Internal(8,i));await(()->floor(s,1)>=3);s.pause();s.reset();rejects(()->s.submit(new Request.Internal(6,1)));await(()->s.snapshot().state()==Simulator.State.RESET);
                for(var v:s.snapshot().elevators())check(v.floor()==1 && v.direction()==Direction.UP && v.pending().isEmpty(),"reset incompleto");
                s.start();Thread.sleep(150);check(s.snapshot().elevators().stream().allMatch(v->v.floor()==1 && v.pending().isEmpty()),"resucitó solicitud");
            }
        });
        test("importación validada, transaccional y solo reset",()->{
            Path good=Files.createTempFile("requests-",".txt"),bad=Files.createTempFile("bad-requests-",".txt");
            Files.writeString(good,"# ejemplo\nINTERNAL 1 5\nEXTERNAL 3 UP\n");Files.writeString(bad,"INTERNAL 1 4\nEXTERNAL 8 UP\n");
            try(var s=system(1,15)){
                boolean rejected=false;try{s.importRequests(bad);}catch(IllegalArgumentException e){rejected=true;check(e.getMessage().contains("2"),"línea del error");}check(rejected && s.snapshot().externalPending()==0,"importación parcial");
                check(s.importRequests(good)==2,"cantidad");s.start();boolean runningRejected=false;try{s.importRequests(good);}catch(IllegalStateException e){runningRejected=true;}check(runningRejected,"importación durante ejecución");idle(s);check(floor(s,1)==5,"importación no ejecutada");
            }finally{Files.delete(good);Files.delete(bad);}
        });
        test("concurrencia: productores, deduplicación y límites",()->{
            try(var s=system(4,5)){
                s.start();ExecutorService pool=Executors.newFixedThreadPool(8);
                try{List<Future<?>> jobs=new ArrayList<>();for(int t=0;t<8;t++){final int seed=t;jobs.add(pool.submit(()->{for(int j=0;j<80;j++){int f=1+(j+seed)%8;s.submit(new Request.Internal(f,1+(j+seed)%4));s.submit(new Request.External(f,f==8?Direction.DOWN:Direction.UP));}}));}for(var job:jobs)job.get(5,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
                idle(s);for(var v:s.snapshot().elevators())check(v.floor()>=1 && v.floor()<=8,"límite");s.reset();await(()->s.snapshot().state()==Simulator.State.RESET);
            }
        });
        test("Stop cancela, reinicio y cierre une todos los threads",()->{
            var s=system(2,40);try{s.start();s.submit(new Request.Internal(8,1));await(()->floor(s,1)>=2);s.stop();int f=floor(s,1);Thread.sleep(100);check(floor(s,1)==f,"movimiento tras stop");s.start();Thread.sleep(80);check(floor(s,1)==f,"solicitudes sobrevivieron stop");}finally{s.close();}check(!s.threadsAlive(),"fuga de threads");
        });
        test("logs por elevador con movimientos, paradas, dirección y reset",()->{
            Path dir=logs.resolve("logging");try(var s=new Simulator(new Simulator.Config(1,4,5,5),dir)){s.start();s.submit(new Request.Internal(3,1));idle(s);s.submit(new Request.Internal(1,1));idle(s);s.reset();await(()->s.snapshot().state()==Simulator.State.RESET);}
            StringBuilder all=new StringBuilder();try(var paths=Files.list(dir)){for(Path p:paths.toList())all.append(Files.readString(p));}
            for(String marker:List.of("MOVE","ARRIVE","STOP","DIRECTION","RESET","SEND","ACK"))check(all.toString().contains(marker),"falta log "+marker);
        });
        System.out.println("Resultado: "+passed+" pruebas aprobadas. Logs: "+logs);
    }
}

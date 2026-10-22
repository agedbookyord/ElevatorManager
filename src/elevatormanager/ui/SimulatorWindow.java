package elevatormanager.ui;
import elevatormanager.core.Simulator;
import elevatormanager.model.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/** Swing solo consulta instantáneas inmutables. I/O y cierre se ejecutan fuera del EDT. */
public final class SimulatorWindow extends JFrame {
    private final JSpinner count=new JSpinner(new SpinnerNumberModel(3,1,16,1));
    private final JSpinner floors=new JSpinner(new SpinnerNumberModel(8,2,100,1));
    private final JSpinner move=new JSpinner(new SpinnerNumberModel(700,1,60000,50));
    private final JSpinner stop=new JSpinner(new SpinnerNumberModel(1000,1,60000,50));
    private final JPanel controls=new JPanel(new FlowLayout(FlowLayout.LEFT));
    private final JPanel requests=new JPanel();
    private final JLabel status=new JLabel("Configure y pulse Preparar");
    private final JTextArea pending=new JTextArea(5,60);
    private final Shaft shaft=new Shaft();
    private Simulator simulator;
    private Simulator.Snapshot snapshot;
    private boolean busy;
    private final Timer timer;
    public SimulatorWindow() {
        super("ElevatorManager — Java 21");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        JPanel north=new JPanel(new GridLayout(0,1));
        JPanel config=new JPanel(new FlowLayout(FlowLayout.LEFT));
        config.add(new JLabel("Elevadores"));config.add(count);config.add(new JLabel("Pisos"));config.add(floors);
        config.add(new JLabel("Movimiento (ms)"));config.add(move);config.add(new JLabel("Parada (ms)"));config.add(stop);
        JButton prepare=new JButton("Preparar");prepare.addActionListener(e->prepare());config.add(prepare);
        north.add(config); north.add(controls);north.add(status);add(north,BorderLayout.NORTH);
        button("Start",()->simulator.start()); button("Stop",()->simulator.stop());button("Reset",()->simulator.reset());
        button("Pausar",()->simulator.pause());button("Reanudar",()->simulator.resume());
        JButton load=new JButton("Importar TXT"); load.addActionListener(e->importFile());controls.add(load);
        JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(shaft),new JScrollPane(requests));
        split.setResizeWeight(.6);add(split,BorderLayout.CENTER);
        pending.setEditable(false);add(new JScrollPane(pending),BorderLayout.SOUTH);
        timer=new Timer(30,e->refresh());timer.start();
        addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){
            if(busy) return;timer.stop(); background(()->{if(simulator!=null)simulator.close();return null;},x->dispose());
        }});
        setSize(1100,760);setLocationRelativeTo(null);
    }
    private int value(JSpinner s){return (Integer)s.getValue();}
    private void button(String label,Runnable action){JButton b=new JButton(label);b.addActionListener(e->attempt(action));controls.add(b);}
    private void attempt(Runnable action){if(busy)return;try{if(simulator==null)throw new IllegalStateException("Prepare el sistema primero");action.run();refresh();}catch(Exception e){error(e);}}
    private void error(Exception e){JOptionPane.showMessageDialog(this,e.getMessage(),"Operación no realizada",JOptionPane.ERROR_MESSAGE);}
    private <T> void background(Callable<T> work,java.util.function.Consumer<T> done){
        busy=true;
        new SwingWorker<T,Void>(){
            protected T doInBackground() throws Exception{return work.call();}
            protected void done(){busy=false;try{done.accept(get());}catch(Exception e){error(e.getCause() instanceof Exception ex?ex:e);}}
        }.execute();
    }
    private void prepare(){
        if(busy)return;
        Simulator.Config cfg=new Simulator.Config(value(count),value(floors),value(move),value(stop));
        Simulator old=simulator;simulator=null;snapshot=null;
        background(()->{if(old!=null)old.close();return new Simulator(cfg,Path.of("logs"));},s->{simulator=s;buildRequests();refresh();});
    }
    private void importFile(){
        if(busy)return;
        if(simulator==null || simulator.snapshot().state()!=Simulator.State.RESET){error(new IllegalStateException("Importar requiere reset completado"));return;}
        JFileChooser chooser=new JFileChooser();if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;
        Simulator current=simulator;
        background(()->current.importRequests(chooser.getSelectedFile().toPath()),n->{status.setText("Importadas: "+n);refresh();});
    }
    private void buildRequests(){
        requests.removeAll();requests.setLayout(new BoxLayout(requests,BoxLayout.Y_AXIS));
        int n=simulator.config().floors();
        for(int id=1;id<=simulator.config().elevators();id++){
            JPanel panel=new JPanel(new FlowLayout(FlowLayout.LEFT));panel.setBorder(BorderFactory.createTitledBorder("Interior elevador "+id));
            final int car=id;
            for(int f=1;f<=n;f++){final int floor=f;JButton b=new JButton(""+f);b.addActionListener(e->attempt(()->simulator.submit(new Request.Internal(floor,car))));panel.add(b);}
            panel.setPreferredSize(new Dimension(430,Math.max(80,((n+7)/8)*36)));requests.add(panel);
        }
        for(int f=n;f>=1;f--){final int floor=f;JPanel row=new JPanel();row.add(new JLabel("Piso "+f));
            if(f<n){JButton b=new JButton("↑");b.addActionListener(e->attempt(()->simulator.submit(new Request.External(floor,Direction.UP))));row.add(b);}
            if(f>1){JButton b=new JButton("↓");b.addActionListener(e->attempt(()->simulator.submit(new Request.External(floor,Direction.DOWN))));row.add(b);}
            requests.add(row);
        }
        requests.revalidate();requests.repaint();
    }
    private void refresh(){
        if(simulator==null)return;
        snapshot=simulator.snapshot();status.setText("Estado: "+snapshot.state()+" | Exteriores/importadas pendientes: "+snapshot.externalPending()+(busy?" | Procesando archivo…":""));
        StringBuilder text=new StringBuilder();
        for(var v:snapshot.elevators())text.append("Elevador ").append(v.id()).append(" | Piso ").append(v.floor()).append(" | ").append(v.direction()).append(" | Destinos ").append(v.pending()).append('\n');
        pending.setText(text.toString());shaft.repaint();
    }
    private final class Shaft extends JPanel {
        Shaft(){setPreferredSize(new Dimension(580,500));setBackground(Color.WHITE);}
        @Override protected void paintComponent(Graphics g){super.paintComponent(g);if(snapshot==null || simulator==null)return;
            int n=simulator.config().floors(), h=Math.max(1,(getHeight()-45)/n), w=Math.max(35,(getWidth()-50)/snapshot.elevators().size());
            for(int f=1;f<=n;f++){int y=20+(n-f)*h;g.setColor(Color.LIGHT_GRAY);g.drawLine(35,y,getWidth(),y);g.setColor(Color.DARK_GRAY);g.drawString(""+f,8,y+12);}
            for(var v:snapshot.elevators()){
                double position=v.floor()+(v.moving()?v.progress()*(v.direction()==Direction.UP?1:-1):0);
                int x=40+(v.id()-1)*w,y=20+(int)((n-position)*h);
                g.setColor(new Color(45,110,185));g.fillRoundRect(x,y,w-8,Math.max(12,h-3),8,8);
                g.setColor(Color.BLACK);g.drawString("E"+v.id()+" "+(v.direction()==Direction.UP?"↑":"↓"),x,getHeight()-10);
            }
        }
    }
}

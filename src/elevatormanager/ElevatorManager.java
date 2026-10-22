package elevatormanager;
import elevatormanager.ui.SimulatorWindow;
import javax.swing.*;
import java.awt.GraphicsEnvironment;
public final class ElevatorManager {
    private ElevatorManager() {}
    public static void main(String[] args) {
        if(GraphicsEnvironment.isHeadless()) {
            System.err.println("Swing necesita una sesión gráfica. Ejecute ./test.sh para probar el motor sin ventanas.");
            return;
        }
        SwingUtilities.invokeLater(() -> new SimulatorWindow().setVisible(true));
    }
}

# ElevatorManager

Simulador educativo de un edificio con varios elevadores. Java 21 y Swing, sin Maven,
Gradle ni bibliotecas externas. El motor se puede probar sin entorno gráfico.

## Requisitos y ejecución

Instale un **JDK 21 completo** (no solo JRE). Compruebe `java -version` y
`javac -version`. Configure `JAVA_HOME` a la carpeta del JDK si no está en PATH.

Linux/macOS o Git Bash, desde la raíz del repositorio:

```bash
./build.sh
./test.sh
./run.sh
```

Los scripts respetan `JAVA_HOME`, compilan con `--release 21` y propagan los errores.
Las clases y listas de fuentes se generan en `out/`, excluido de Git.
En el entorno cloud de desarrollo se instaló un JDK verificado en
`/workspace/toolchains/jdk-21.0.8+9`; para usarlo:

```bash
export JAVA_HOME=/workspace/toolchains/jdk-21.0.8+9
./test.sh
```

Windows PowerShell, sin Bash:

```powershell
New-Item -ItemType Directory -Force out/main, out/test | Out-Null
$mainSources = (Get-ChildItem src -Recurse -Filter *.java).FullName
javac -encoding UTF-8 --release 21 -d out/main $mainSources
$testSources = (Get-ChildItem tests -Recurse -Filter *.java).FullName
javac -encoding UTF-8 --release 21 -cp out/main -d out/test $testSources
java -Djava.awt.headless=true -cp 'out/main;out/test' elevatormanager.SimulatorTest
java -cp out/main elevatormanager.ElevatorManager
```

### VS Code

1. Abra esta carpeta y tenga instalado JDK 21. La extensión opcional **Extension Pack
   for Java** aporta depuración y autocompletado; la aplicación no depende de ella.
2. Si VS Code no encuentra el JDK, use `Java: Configure Java Runtime` y seleccione JDK 21.
3. Ejecute los scripts desde la terminal integrada, o abra `ElevatorManager.java` y
   pulse Run/Debug. Se incluye `.vscode/launch.json`.
4. Swing requiere escritorio o servidor gráfico. En servidores headless use las
   pruebas del motor. No se publica una vista web de Swing.

## Uso de la interfaz

- Elija 1–16 elevadores, 2–100 pisos y tiempos de movimiento/parada de 1–60000 ms.
  Pulse **Preparar** para construir el sistema. Volver a preparar cierra el anterior
  y descarta su estado; los nuevos valores solo se aplican al preparar.
- **Start** comienza la simulación y envía las solicitudes importadas.
- Los botones interiores solicitan un destino para esa cabina. Los exteriores
  solicitan recogida en una dirección; los extremos solo muestran la dirección válida.
  El modelo no simula personas, capacidad física ni selección automática del destino
  tras una recogida: use un botón interior para continuar el viaje.
- **Pausar/Reanudar** congelan/restablecen movimiento, esperas y asignación. Se pueden
  encolar solicitudes durante la pausa. El dibujo interpola entre pisos; el piso
  numérico cambia al completar el tramo.
- **Stop** cancela solicitudes y detiene las cabinas en el último piso alcanzado.
  **Start** permite continuar desde allí sin recuperar solicitudes canceladas.
- **Reset** cancela solicitudes e importaciones, invalida comandos en tránsito y
  regresa cada cabina al piso 1 usando el tiempo configurado entre pisos. Si estaba
  pausado, el regreso se ejecuta igualmente. Al completar, todas quedan hacia arriba
  en estado `RESET`, listas para importar y volver a pulsar Start. No se admiten
  solicitudes durante el regreso. Un tramo interrumpido se cancela desde el último
  piso alcanzado; no se modela un frenado físico a mitad de tramo.
- El panel inferior muestra piso, dirección y solicitudes pendientes (`I`, `↑`, `↓`).
  El indicador superior muestra estado y solicitudes todavía no asignadas/importadas.
- Cerrar la ventana libera threads y archivos. Preparación, importación y cierre
  usan `SwingWorker`; los movimientos nunca se ejecutan en el EDT.

## Importación TXT

Solo se confirma una importación en `RESET` completado (también es el estado inicial
tras Preparar). No se permite en `STOPPED`, `RUNNING`, `PAUSED` ni `RESETTING`.
UTF-8, una solicitud por línea, palabras en mayúsculas, campos separados por espacios:

```text
# INTERNAL <id-elevador> <piso-destino>
INTERNAL 1 5
# EXTERNAL <piso-origen> <UP|DOWN>
EXTERNAL 3 UP
EXTERNAL 7 DOWN
```

Las líneas vacías y los comentarios completos iniciados con `#` se ignoran.
Los IDs empiezan en 1. Se comprueban formato, rangos y dirección exterior válida;
si falla una línea se informa su número y **no se incorpora ninguna línea del archivo**.
Las importaciones válidas se acumulan y se despachan al pulsar Start; Reset las borra.
`examples/requests.txt` sirve para la configuración predeterminada de 8 pisos.
No hay tiempos de llegada en el formato: todas las solicitudes se envían al iniciar.

## Diseño y algoritmo

- `ElevatorManager.java`: entrada en el Event Dispatch Thread.
- `model.Request`: clase abstracta sellada con herencia real. `Internal` añade ID de
  cabina; `External` añade dirección. Ambas comparten piso y validación del dominio,
  pero el administrador las enruta de modo distinto. La herencia modela las dos
  variantes de una solicitud; no se extiende Thread para confundir tarea y ejecución.
- `core.Simulator`: estado del sistema, administrador y cabinas. Cada cabina implementa
  Runnable y tiene su propio Thread; otro Thread ejecuta al administrador.
- `ui.SimulatorWindow`: Swing, dibujo animado con Timer e instantáneas inmutables.
- `tests/SimulatorTest`: runner sin dependencias con timeouts y aserciones explícitas.

El administrador asigna cada llamada exterior minimizando:

```text
abs(pisoCabina - pisoLlamada) + 2 * solicitudesPendientes + penalización
penalización = 2 * cantidadPisos si la cabina está ocupada y
               la llamada queda detrás o pide otra dirección; 0 en otro caso
```

Empates favorecen el ID menor. Es una heurística, no un cálculo exacto de tiempos;
las llamadas asignadas no se reasignan. La carga cuenta paradas/comandos pendientes,
no pasajeros. Las colas de entrada conservan orden FIFO; las paradas se deduplican
por piso y tipo dentro de cada cabina usando conjuntos ordenados.

Cada cabina realiza un barrido direccional tipo LOOK: atiende destinos interiores
más llamadas de su dirección al alcanzar cada piso. Continúa mientras exista
alguna solicitud por delante. Al agotar ese sentido invierte y reevalúa el piso
actual antes de salir, recogiendo allí llamadas del nuevo sentido. Una llamada de
sentido opuesto por delante se conserva para el retorno y puede determinar el
extremo del barrido. Las nuevas llamadas compatibles se recogen en camino; no se
invierte antes de completar las paradas del sentido actual. Con tráfico infinito
no se garantiza un límite de espera; no se modelan prioridades de emergencia.

### Sincronización y protocolo textual

Un monitor protege todas las transiciones, conjuntos y lecturas. Las entradas del
administrador y de las cabinas son `LinkedBlockingQueue`. Se utiliza `wait/notifyAll`
para ceder el monitor durante las esperas, sin espera activa; las cabinas avanzan
independientemente aunque sus transiciones discretas se serializan. El reloj usa
`System.nanoTime`; el tiempo pausado no consume el viaje o la parada. Swing lee
instantáneas y nunca recibe referencias a colecciones mutables del motor.

El administrador envía textos reales que la cabina analiza, envueltos en una
**generación** de sesión:

```text
SEND 0 INTERNAL 5
SEND 0 EXTERNAL 3 UP
ACK 0 EXTERNAL 3 UP
SEND 1 RESET
ACK 1 RESET
```

`SEND/ACK` son las entradas de registro; el payload es `INTERNAL <piso>`,
`EXTERNAL <piso> <dirección>` o `RESET`. La cola selecciona la cabina destinataria.
Los comandos son internos, no un socket ni una consola pública. Reset y Stop
incrementan la generación y limpian colas/conjuntos. Las esperas comprueban esa
generación antes de confirmar un movimiento para evitar solicitudes antiguas.

Se registran `MOVE`, `ARRIVE`, `STOP`, `DIRECTION`, `RESET`, `SEND` y `ACK` con
`java.util.logging`, en consola y archivos `logs/elevator-<id>-<u>.log` por cabina.
`<u>` evita colisiones si existen otras instancias. Los archivos usan append, no
rotación; se pueden borrar con la aplicación cerrada. `RESET COMPLETE` es global.

## Pruebas y alcance de validación

`./test.sh` ejecuta 11 casos: validación de entradas; puntaje de asignación; asignación
real por cercanía; movimientos independientes; recogida en camino sin inversión
prematura; pausa/reanudación; reset durante viaje con cancelación; importación
transaccional; ocho productores concurrentes (1280 solicitudes); Stop/reinicio y
cierre de threads; contenido de logs por cabina. Cualquier excepción, timeout o
aserción falla el proceso. Los logs de prueba se guardan en una carpeta temporal
anunciada por el runner. El motor conserva pequeñas esperas reales para ejercitar
intercalados de threads.

Validado en este entorno: compilación Java 21 y los 11 casos del motor en modo
headless. La interfaz compila, pero **no se han probado ventanas, clics ni animación
visible** en este servidor sin escritorio. Verificación manual pendiente en un
escritorio: preparar, Start, botones interiores/exteriores, observar recogidas y
animación, Pausar/Reanudar, Reset desde pausa, importar un archivo válido y otro
inválido, Stop/Start y cerrar sin procesos residuales.
